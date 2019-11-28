package com.nytimes.android.external.store4.impl.multicast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Simple actor implementation abstracting away Coroutine.actor since it is deprecated.
 * It also enforces a 0 capacity buffer.
 */
@Suppress("EXPERIMENTAL_API_USAGE")
@ExperimentalCoroutinesApi
abstract class StoreRealActor<T>(
        scope: CoroutineScope
) {
    private val inboundChannel: SendChannel<Any?>
    private val closeCompleted = CompletableDeferred<Unit>()
    private val didClose = AtomicBoolean(false)

    init {
        inboundChannel = scope.actor(
                capacity = 0
        ) {
            try {
                for (msg in channel) {
                    if (msg === CLOSE_TOKEN) {
                        doClose()
                        break
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        handle(msg as T)
                    }
                }
            } finally {
                doClose()
            }
        }
    }

    private fun doClose() {
        if (didClose.compareAndSet(false, true)) {
            try {
                onClosed()
            } finally {
                inboundChannel.close()
                closeCompleted.complete(Unit)
            }
        }
    }

    open fun onClosed() {

    }

    abstract suspend fun handle(msg: T)

    suspend fun send(msg: T) {
        inboundChannel.send(msg)
    }

    suspend fun close() {
        // using a custom token to close so that we can gracefully close the downstream
        inboundChannel.send(CLOSE_TOKEN)
        // wait until close is done done
        closeCompleted.await()
    }

    companion object {
        val CLOSE_TOKEN = Any()
    }
}
