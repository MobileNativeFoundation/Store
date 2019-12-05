package com.dropbox.android.external.store4.impl.multicast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform

/**
 * Like a publish, shares 1 upstream value with multiple downstream receiver.
 * It has one store specific behavior where upstream flow is suspended until at least 1 downstream
 * flow emits the value to ensure we don't abuse the upstream flow of downstream cannot keep up.
 */
@FlowPreview
@ExperimentalCoroutinesApi
internal class Multicaster<T>(
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
    // TODO does this have to be a method or just a flow ? Will decide when actual implementation
    //  happens
    private val source: () -> Flow<T>,

    /**
     * If true, downstream is never closed by the multicaster unless upstream throws an error.
     * Instead, it is kept open and if a new downstream shows up that causes us to restart the flow,
     * it will receive values as well.
     */
    private val piggybackingDownstream: Boolean = false,
    /**
     * Called when upstream dispatches a value.
     */
    private val onEach: suspend (T) -> Unit
) {

    private val channelManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChannelManager(
            scope = scope,
            bufferSize = bufferSize,
            onActive = {
                SharedFlowProducer(
                    scope = scope,
                    src = source(),
                    channelManager = it
                )
            },
            piggybackingDownstream = piggybackingDownstream,
            onEach = onEach
        )
    }

    fun create(): Flow<T> {
        val channel = Channel<ChannelManager.Message.DispatchValue<T>>(Channel.UNLIMITED)
        return channel.consumeAsFlow()
            .onStart {
                channelManager.send(
                    ChannelManager.Message.AddChannel(
                        channel
                    )
                )
            }
            .transform {
                emit(it.value)
                it.delivered.complete(Unit)
            }.onCompletion {
                channelManager.send(
                    ChannelManager.Message.RemoveChannel(
                        channel
                    )
                )
            }
    }

    suspend fun close() {
        channelManager.close()
    }
}