package org.mobilenativefoundation.store.store5

import kotlinx.datetime.Clock
import org.mobilenativefoundation.store.store5.impl.RealStoreWriteRequest


interface StoreWriteRequest<Key : Any, CommonRepresentation : Any> {
    val key: Key
    val input: CommonRepresentation
    val created: Long
    val onCompletions: List<Any>

    companion object {
        fun <Key : Any, CommonRepresentation : Any> of(
            key: Key,
            input: CommonRepresentation,
            onCompletions: List<Any>,
            created: Long = Clock.System.now().toEpochMilliseconds(),
        ): StoreWriteRequest<Key, CommonRepresentation> = RealStoreWriteRequest(key, input, created, onCompletions)
    }
}