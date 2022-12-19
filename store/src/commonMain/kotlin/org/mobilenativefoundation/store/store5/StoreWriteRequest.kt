package org.mobilenativefoundation.store.store5

import kotlinx.datetime.Clock
import org.mobilenativefoundation.store.store5.impl.OnStoreWriteCompletion
import org.mobilenativefoundation.store.store5.impl.RealStoreWriteRequest

interface StoreWriteRequest<Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> {
    val key: Key
    val input: CommonRepresentation
    val created: Long
    val onCompletions: List<OnStoreWriteCompletion<NetworkWriteResponse>>

    companion object {
        fun <Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> of(
            key: Key,
            input: CommonRepresentation,
            onCompletions: List<OnStoreWriteCompletion<NetworkWriteResponse>>,
            created: Long = Clock.System.now().toEpochMilliseconds(),
        ): StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse> = RealStoreWriteRequest(key, input, created, onCompletions)
    }
}
