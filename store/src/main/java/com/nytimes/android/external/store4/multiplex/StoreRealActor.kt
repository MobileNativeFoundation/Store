package com.nytimes.android.external.store4.multiplex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor

/**
 * Simple actor implementation abstracting away Coroutine.actor since it is deprecated.
 * It also enforces a 0 capacity buffer.
 */
@Suppress("EXPERIMENTAL_API_USAGE")
@ExperimentalCoroutinesApi
abstract class StoreRealActor<T>(
    scope: CoroutineScope
) {
    val inboundChannel: SendChannel<T>

    init {
        inboundChannel = scope.actor(
            capacity = 0
        ) {
            channel.invokeOnClose {
                onClosed()
            }
            for (msg in channel) {
                handle(msg)
            }
        }
    }

    open fun onClosed() {

    }

    abstract suspend fun handle(msg: T)

    suspend fun send(msg: T) {
        inboundChannel.send(msg)
    }

    fun close() {
        inboundChannel.close()
    }
}
