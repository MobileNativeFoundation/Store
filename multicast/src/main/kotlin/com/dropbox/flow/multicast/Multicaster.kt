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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext

/**
 * Like a publish, shares 1 upstream value with multiple downstream receiver.
 *
 * This operation still keeps the upstream flow cold such that it is suspended until at least 1
 * downstream value collects the latest dispatched value OR a new downstream is added while [buffer]
 * is empty.
 */
class Multicaster<T>(
    /**
     * The [CoroutineScope] to use for upstream subscription
     */
    private val scope: CoroutineScope,
    /**
     * The buffer size that is used only if the upstream has not complete yet.
     * Defaults to 0.
     */
    bufferSize: Int = 0,
    /**
     * Source function to create a new flow when necessary.
     */
    private val source: Flow<T>,

    /**
     * If true, downstream is never closed by the multicaster unless upstream throws an error.
     * Instead, it is kept open and if a new downstream shows up that causes us to restart the flow,
     * it will receive values as well.
     */
    private val piggybackingDownstream: Boolean = false,
    /**
     * If true, an active upstream will stay alive even if all downstreams are closed. A downstream
     * coming in later will receive a value from the live upstream.
     *
     * The upstream will be kept alive until [scope] cancels or [close] is called.
     */
    private val keepUpstreamAlive: Boolean = false,
    /**
     * Called when upstream dispatches a value.
     */
    private val onEach: suspend (T) -> Unit
) {

    internal var channelManagerFactory: () -> ChannelManager<T> = {
        StoreChannelManager(
            scope = scope,
            bufferSize = bufferSize,
            upstream = source,
            piggybackingDownstream = piggybackingDownstream,
            keepUpstreamAlive = keepUpstreamAlive,
            onEach = onEach
        )
    }

    private val channelManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { channelManagerFactory() }

    /**
     * Gets a new downstream flow. Collectors of this flow will share values dispatched by a
     * single upstream [source] Flow.
     *
     * @param piggybackOnly if true this downstream will not cause a new upstream to start running
     * (which only happens if no upstream is running already, e.g if this is the first downstream
     * added). [piggybackOnly] is only valid if [piggybackingDownstream] is enabled for this
     * [Multicaster].
     */
    fun newDownstream(piggybackOnly: Boolean = false): Flow<T> {
        check(!piggybackOnly || piggybackingDownstream) {
            "cannot create a piggyback only flow when piggybackDownstream is disabled"
        }
        return flow {
            val channel = Channel<ChannelManager.Message.Dispatch.Value<T>>(Channel.UNLIMITED)
            val subFlow = channel.consumeAsFlow()
                .onStart {
                    try {
                        channelManager.addDownstream(channel, piggybackOnly)
                    } catch (closed: ClosedSendChannelException) {
                        // before we could start, channel manager was closed.
                        // close our downstream manually as it won't be closed by the ChannelManager
                        channel.close()
                    }
                }
                .transform<ChannelManager.Message.Dispatch.Value<T>, T> {
                    emit(it.value)
                    it.delivered.complete(Unit)
                }.onCompletion {
                    withContext(NonCancellable) {
                        try {
                            channelManager.removeDownstream(channel)
                        } catch (closed: ClosedSendChannelException) {
                            // ignore, we might be closed because ChannelManager is closed
                        }
                    }
                }
            emitAll(subFlow)
        }
    }

    /**
     * Closes the [Multicaster]. All current collectors on the [flow] will complete and any new
     * collector will receive 0 values and immediately close even if the [bufferSize] is set to a
     * positive value.
     *
     * This is an idempotent operation.
     */
    suspend fun close() {
        channelManager.close()
    }
}
