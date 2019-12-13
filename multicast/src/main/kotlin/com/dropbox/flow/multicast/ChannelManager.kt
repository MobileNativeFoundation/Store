/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dropbox.flow.multicast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import java.util.ArrayDeque
import java.util.Collections

// TODO uncomment when [ExperimentalCoroutinesApi] is fixed to support typealias
// @ExperimentalCoroutinesApi
internal typealias ChannelManagerInbox<T> = suspend (ChannelManager.Message.Dispatch<T>) -> Unit

/**
 * This actor helps tracking active channels and is able to dispatch values to each of them
 * in parallel. As soon as one of them receives the value, the ack in the dispatch message is
 * completed so that the sender can continue for the next item.
 */
@ExperimentalCoroutinesApi
internal class ChannelManager<T>(
    /**
     * The scope in which ChannelManager actor runs
     */
    private val scope: CoroutineScope,
    /**
     * The buffer size that is used while the upstream is active
     */
    private val bufferSize: Int,
    /**
     * If true, downstream is never closed by the ChannelManager unless upstream throws an error.
     * Instead, it is kept open and if a new downstream shows up that causes us to restart the flow,
     * it will receive values as well.
     */
    private val piggybackingDownstream: Boolean = false,
    /**
     * Called when a value is dispatched
     */
    private val onEach: suspend (T) -> Unit,

    private val upstream: Flow<T>
) : StoreRealActor<ChannelManager.Message<T>>(scope) {
    private val buffer = Buffer<T>(bufferSize)
    /**
     * The current producer
     */
    private var producer: SharedFlowProducer<T>? = null

    /**
     * Tracks whether we've ever dispatched value or error from the current producer.
     * Reset when producer finishes.
     */
    private var dispatchedValue: Boolean = false

    /**
     * List of downstream collectors.
     */
    private val channels = mutableListOf<ChannelEntry<T>>()

    override suspend fun handle(msg: Message<T>) {
        when (msg) {
            is Message.AddChannel -> doAdd(msg)
            is Message.RemoveChannel -> doRemove(msg.channel)
            is Message.Dispatch.Value -> doDispatchValue(msg)
            is Message.Dispatch.Error -> doDispatchError(msg)
            is Message.Dispatch.UpstreamFinished -> doHandleUpstreamClose(msg.producer)
        }
    }

    suspend fun addDownstream(channel: SendChannel<Message.Dispatch.Value<T>>) =
        send(Message.AddChannel(channel))

    suspend fun removeDownstream(channel: SendChannel<Message.Dispatch.Value<T>>) =
        send(Message.RemoveChannel(channel))

    /**
     * Called when the channel manager is active (e.g. it has downstream collectors and needs a
     * producer)
     */
    private fun newProducer() = SharedFlowProducer(scope, upstream, ::send)

    /**
     * We are closing. Do a cleanup on existing channels where we'll close them and also decide
     * on the list of leftovers.
     */
    private fun doHandleUpstreamClose(producer: SharedFlowProducer<T>?) {
        if (this.producer !== producer) {
            return
        }
        val piggyBacked = mutableListOf<ChannelEntry<T>>()
        val leftovers = mutableListOf<ChannelEntry<T>>()
        channels.forEach {
            when {
                it.receivedValue -> {
                    if (!piggybackingDownstream) {
                        it.close()
                    } else {
                        piggyBacked.add(it)
                    }
                }
                dispatchedValue ->
                    // we dispatched a value but this channel didn't receive so put it into
                    // leftovers
                    leftovers.add(it)
                else -> { // upstream didn't dispatch
                    if (!piggybackingDownstream) {
                        it.close()
                    } else {
                        piggyBacked.add(it)
                    }
                }
            }
        }
        channels.clear() // empty references
        channels.addAll(leftovers)
        channels.addAll(piggyBacked)
        this.producer = null
        // we only reactivate if leftovers is not empty
        if (leftovers.isNotEmpty()) {
            activateIfNecessary()
        }
    }

    override fun onClosed() {
        channels.forEach {
            it.close()
        }
        channels.clear()
        producer?.cancel()
    }

    /**
     * Dispatch value to all downstream collectors.
     */
    private suspend fun doDispatchValue(msg: Message.Dispatch.Value<T>) {
        onEach(msg.value)
        buffer.add(msg)
        dispatchedValue = true
        channels.forEach {
            it.dispatchValue(msg)
        }
    }

    /**
     * Dispatch an upstream error to downstream collectors.
     */
    private fun doDispatchError(msg: Message.Dispatch.Error<T>) {
        // dispatching error is as good as dispatching value
        dispatchedValue = true
        channels.forEach {
            it.dispatchError(msg.error)
        }
    }

    /**
     * Remove a downstream collector.
     */
    private suspend fun doRemove(channel: SendChannel<Message.Dispatch.Value<T>>) {
        val index = channels.indexOfFirst {
            it.hasChannel(channel)
        }
        if (index >= 0) {
            channels.removeAt(index)
            if (channels.isEmpty()) {
                producer?.cancelAndJoin()
            }
        }
    }

    /**
     * Add a new downstream collector
     */
    private suspend fun doAdd(msg: Message.AddChannel<T>) {
        addEntry(
            entry = ChannelEntry(
                channel = msg.channel
            )
        )
        activateIfNecessary()
    }

    private fun activateIfNecessary() {
        if (producer == null) {
            producer = newProducer()
            dispatchedValue = false
            producer!!.start()
        }
    }

    /**
     * Internally add the new downstream collector to our list, send it anything buffered.
     */
    private suspend fun addEntry(entry: ChannelEntry<T>) {
        val new = channels.none {
            it.hasChannel(entry)
        }
        check(new) {
            "$entry is already in the list."
        }
        check(!entry.receivedValue) {
            "$entry already received a value"
        }
        channels.add(entry)
        if (buffer.items.isNotEmpty()) {
            // if there is anything in the buffer, send it
            buffer.items.forEach {
                entry.dispatchValue(it)
            }
        }
    }

    /**
     * Holder for each downstream collector
     */
    internal data class ChannelEntry<T>(
        /**
         * The channel used by the collector
         */
        private val channel: SendChannel<Message.Dispatch.Value<T>>,
        /**
         * Tracking whether we've ever dispatched a value or an error to downstream
         */
        private var _receivedValue: Boolean = false
    ) {
        val receivedValue
            get() = _receivedValue

        suspend fun dispatchValue(value: Message.Dispatch.Value<T>) {
            _receivedValue = true
            channel.send(value)
        }

        fun dispatchError(error: Throwable) {
            _receivedValue = true
            channel.close(error)
        }

        fun close() {
            channel.close()
        }

        fun hasChannel(channel: SendChannel<Message.Dispatch.Value<T>>) = this.channel === channel

        fun hasChannel(entry: ChannelEntry<T>) = this.channel === entry.channel
    }

    /**
     * Messages accepted by the [ChannelManager].
     */
    sealed class Message<T> {
        /**
         * Add a new channel, that means a new downstream subscriber
         */
        class AddChannel<T>(
            val channel: SendChannel<Dispatch.Value<T>>
        ) : Message<T>()

        /**
         * Remove a downstream subscriber, that means it completed
         */
        class RemoveChannel<T>(val channel: SendChannel<Dispatch.Value<T>>) : Message<T>()

        sealed class Dispatch<T> : Message<T>() {
            /**
             * Upstream dispatched a new value, send it to all downstream items
             */
            class Value<T>(
                /**
                 * The value dispatched by the upstream
                 */
                val value: T,
                /**
                 * Ack that is completed by all receiver. Upstream producer will await this before asking
                 * for a new value from upstream
                 */
                val delivered: CompletableDeferred<Unit>
            ) : Dispatch<T>()

            /**
             * Upstream dispatched an error, send it to all downstream items
             */
            class Error<T>(
                /**
                 * The error sent by the upstream
                 */
                val error: Throwable
            ) : Dispatch<T>()

            class UpstreamFinished<T>(
                /**
                 * SharedFlowProducer finished emitting
                 */
                val producer: SharedFlowProducer<T>
            ) : Dispatch<T>()
        }
    }

    /**
     * Buffer implementation for any late arrivals.
     */
    private interface Buffer<T> {
        fun add(item: Message.Dispatch.Value<T>)
        val items: Collection<Message.Dispatch.Value<T>>
    }

    /**
     * Default implementation of buffer which does not buffer anything.
     */
    private class NoBuffer<T> : Buffer<T> {
        override val items: Collection<Message.Dispatch.Value<T>>
            get() = Collections.emptyList()

        // ignore
        override fun add(item: Message.Dispatch.Value<T>) = Unit
    }

    /**
     * Create a new buffer insteance based on the provided limit.
     */
    @Suppress("FunctionName")
    private fun <T> Buffer(limit: Int): Buffer<T> = if (limit > 0) {
        BufferImpl(limit)
    } else {
        NoBuffer()
    }

    /**
     * A real buffer implementation that has a FIFO queue.
     */
    private class BufferImpl<T>(private val limit: Int) :
        Buffer<T> {
        override val items = ArrayDeque<Message.Dispatch.Value<T>>(limit.coerceAtMost(10))
        override fun add(item: Message.Dispatch.Value<T>) {
            while (items.size >= limit) {
                items.pollFirst()
            }
            items.offerLast(item)
        }
    }
}

@ExperimentalCoroutinesApi
internal fun <T> ChannelManager.Message.Dispatch.Value<T>.markDelivered() =
    delivered.complete(Unit)
