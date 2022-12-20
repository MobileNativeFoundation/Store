package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.Flow

interface MutableStore<Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> : Store<Key, CommonRepresentation> {
    @ExperimentalStoreApi
    fun stream(stream: Flow<StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse>>): Flow<StoreWriteResponse<NetworkWriteResponse>>

    @ExperimentalStoreApi
    suspend fun write(request: StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse>): StoreWriteResponse<NetworkWriteResponse>
}
