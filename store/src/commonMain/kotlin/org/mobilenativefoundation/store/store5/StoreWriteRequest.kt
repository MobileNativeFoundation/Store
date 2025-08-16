package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.store5.impl.OnStoreWriteCompletion
import org.mobilenativefoundation.store.store5.impl.RealStoreWriteRequest
import org.mobilenativefoundation.store.store5.impl.extensions.currentTimeMillis

interface StoreWriteRequest<Key : Any, Output : Any, Response : Any> {
    val key: Key
    val value: Output
    val created: Long
    val onCompletions: List<OnStoreWriteCompletion>?

    companion object {
        fun <Key : Any, Output : Any, Response : Any> of(
            key: Key,
            value: Output,
            onCompletions: List<OnStoreWriteCompletion>? = null,
            created: Long = currentTimeMillis(),
        ): StoreWriteRequest<Key, Output, Response> = RealStoreWriteRequest(key, value, created, onCompletions)
    }
}
