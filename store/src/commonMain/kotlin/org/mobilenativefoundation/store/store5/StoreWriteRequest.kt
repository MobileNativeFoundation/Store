package org.mobilenativefoundation.store.store5

import kotlinx.datetime.Clock
import org.mobilenativefoundation.store.store5.impl.OnStoreWriteCompletion
import org.mobilenativefoundation.store.store5.impl.RealStoreWriteRequest

interface StoreWriteRequest<Key : Any, Common : Any, Response : Any> {
    val key: Key
    val input: Common
    val created: Long
    val onCompletions: List<OnStoreWriteCompletion>?

    companion object {
        fun <Key : Any, Common : Any, Response : Any> of(
            key: Key,
            input: Common,
            onCompletions: List<OnStoreWriteCompletion>? = null,
            created: Long = Clock.System.now().toEpochMilliseconds(),
        ): StoreWriteRequest<Key, Common, Response> = RealStoreWriteRequest(key, input, created, onCompletions)
    }
}
