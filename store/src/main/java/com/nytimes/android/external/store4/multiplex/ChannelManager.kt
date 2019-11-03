package com.nytimes.android.external.store4.multiplex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque
import java.util.Collections

/**
 * This actor helps tracking active channels and is able to dispatch values to each of them
 * in parallel. As soon as one of them receives the value, the ack in the dispatch message is
 * completed so that the sender can continue for the next item.
 */
@ExperimentalCoroutinesApi
class ChannelManager<T>(
    /**
     * The scope in which ChannelManager actor runs
     */
    scope: CoroutineScope,
    /**
     * The buffer size that is used while the upstream is active
     */
    bufferSize: Int,
    private val onEach: suspend (T) -> Unit,
    /**
     * Called when the channel manager is active (e.g. it has downstream collectors and needs a
     * producer)
     */
    private val onActive: (ChannelManager<T>) -> SharedFlowProducer<T>
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
            is Message.AddLeftovers -> doAddLefovers(msg.leftovers)
            is Message.AddChannel -> doAdd(msg)
            is Message.RemoveChannel -> doRemove(msg.channel)
            is Message.DispatchValue -> doDispatchValue(msg)
            is Message.DispatchError -> doDispatchError(msg)
            is Message.UpstreamFinished -> doHandleUpstreamClose(msg.producer)
        }
    }

    /**
     * We are closing. Do a cleanup on existing channels where we'll close them and also decide
     * on the list of leftovers.
     */
    fun doHandleUpstreamClose(producer: SharedFlowProducer<T>?) {
        if (this.producer !== producer) {
            return
        }
        val leftovers = mutableListOf<ChannelEntry<T>>()
        channels.forEach {
            when {
                it.receivedValue -> it.close()
                dispatchedValue ->
                    // we dispatched a value but this channel didn't receive so put it into
                    // leftovers
                    leftovers.add(it)
                else -> // upstream didn't dispatch
                    it.close()
            }
        }
        channels.clear() // empty references
        channels.addAll(leftovers)
        this.producer = null
        if (leftovers.isNotEmpty()) {
            activateIfNecessary(true)
        }
    }

    override fun onClosed() {
        producer?.cancel()
    }

    /**
     * Dispatch value to all downstream collectors.
     */
    private suspend fun doDispatchValue(msg: Message.DispatchValue<T>) {
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
    private fun doDispatchError(msg: Message.DispatchError<T>) {
        // dispatching error is as good as dispatching value
        dispatchedValue = true
        channels.forEach {
            it.dispatchError(msg.error)
        }
    }

    /**
     * Remove a downstream collector.
     */
    private suspend fun doRemove(channel: Channel<Message.DispatchValue<T>>) {
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
     * We've received some leftovers from the previous [ChannelManager]. Add them to our list.
     */
    private suspend fun doAddLefovers(leftovers: List<ChannelEntry<T>>) {
        val wasEmpty = channels.isEmpty()
        leftovers.forEach { channelEntry ->
            addEntry(
                entry = channelEntry.copy(_receivedValue = false)
            )
        }
        activateIfNecessary(wasEmpty)
    }

    /**
     * Add a new downstream collector
     */
    private suspend fun doAdd(msg: Message.AddChannel<T>) {
        val wasEmpty = channels.isEmpty()

        addEntry(
            entry = ChannelEntry(
                channel = msg.channel
            )
        )
        activateIfNecessary(wasEmpty)
    }

    private fun activateIfNecessary(wasEmpty: Boolean) {
        if (producer == null && wasEmpty) {
            producer = onActive(this)
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
        private val channel: Channel<Message.DispatchValue<T>>,
        /**
         * Tracking whether we've ever dispatched a value or an error to downstream
         */
        private var _receivedValue: Boolean = false
    ) {
        val receivedValue
            get() = _receivedValue

        suspend fun dispatchValue(value: Message.DispatchValue<T>) {
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

        fun hasChannel(channel: Channel<Message.DispatchValue<T>>) = this.channel === channel

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
            val channel: Channel<DispatchValue<T>>
        ) : Message<T>()

        /**
         * Add multiple channels. Happens when we are carrying over leftovers from a previous
         * manager
         */
        internal class AddLeftovers<T>(val leftovers: List<ChannelEntry<T>>) :
            Message<T>()

        /**
         * Remove a downstream subscriber, that means it completed
         */
        class RemoveChannel<T>(val channel: Channel<DispatchValue<T>>) : Message<T>()

        /**
         * Upstream dispatched a new value, send it to all downstream items
         */
        class DispatchValue<T>(
            /**
             * The value dispatched by the upstream
             */
            val value: T,
            /**
             * Ack that is completed by all receiver. Upstream producer will await this before asking
             * for a new value from upstream
             */
            val delivered: CompletableDeferred<Unit>
        ) : Message<T>()

        /**
         * Upstream dispatched an error, send it to all downstream items
         */
        class DispatchError<T>(
            /**
             * The error sent by the upstream
             */
            val error: Throwable
        ) : Message<T>()

        class UpstreamFinished<T>(
            /**
             * SharedFlowProducer finished emitting
             */
            val producer: SharedFlowProducer<T>
        ) : Message<T>()
    }

    /**
     * Buffer implementation for any late arrivals.
     */
    private interface Buffer<T> {
        fun add(item: Message.DispatchValue<T>)
        val items: Collection<Message.DispatchValue<T>>
    }

    /**
     * Default implementation of buffer which does not buffer anything.
     */
    private class NoBuffer<T> : Buffer<T> {
        override val items: Collection<Message.DispatchValue<T>>
            get() = Collections.emptyList()


        override fun add(item: Message.DispatchValue<T>) {
            // ignore
        }
    }

    /**
     * Create a new buffer insteance based on the provided limit.
     */
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
        override val items = ArrayDeque<Message.DispatchValue<T>>(limit.coerceAtMost(10))
        override fun add(item: Message.DispatchValue<T>) {
            while (items.size >= limit) {
                items.pollFirst()
            }
            items.offerLast(item)
        }
    }
}
