package com.dropbox.flow.multicast

import kotlinx.coroutines.CoroutineScope

internal expect abstract class StoreRealActor<T>(
    scope: CoroutineScope
) {
    open fun onClosed()
    abstract suspend fun handle(msg: T)
    suspend fun send(msg: T)
    suspend fun close()
}
