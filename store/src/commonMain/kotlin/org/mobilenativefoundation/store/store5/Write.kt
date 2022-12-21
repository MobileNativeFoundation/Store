package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.Flow

interface Write<Key : Any, CommonRepresentation : Any> {
    @ExperimentalStoreApi
    suspend fun <NetworkWriteResponse : Any> write(request: StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse>): StoreWriteResponse
    interface Stream<Key : Any, CommonRepresentation : Any> {
        @ExperimentalStoreApi
        fun <NetworkWriteResponse : Any> stream(requestStream: Flow<StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse>>): Flow<StoreWriteResponse>
    }
}
