package org.mobilenativefoundation.store.store5.impl

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class MutableStoreAdapterContext(
    val store: Any,
    val storeKey: Any,
    val parent: MutableStoreAdapterContext?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MutableStoreAdapterContext>
}

internal suspend fun checkMutableStoreEntry(store: Any, key: Any? = null) {
    var frame = currentCoroutineContext()[MutableStoreAdapterContext]
    while (frame != null) {
        check(frame.store !== store || (key != null && frame.storeKey != key)) {
            "Recursive MutableStore adapter operation for key=$key."
        }
        frame = frame.parent
    }
}

internal suspend fun <T> withMutableStoreAdapter(
    store: Any,
    key: Any,
    block: suspend () -> T,
): T {
    val parent = currentCoroutineContext()[MutableStoreAdapterContext]
    return withContext(MutableStoreAdapterContext(store, key, parent)) { block() }
}
