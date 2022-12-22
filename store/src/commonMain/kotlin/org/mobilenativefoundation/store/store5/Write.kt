package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.Flow

interface Write<Key : Any, Common : Any> {
    @ExperimentalStoreApi
    suspend fun <Response : Any> write(request: StoreWriteRequest<Key, Common, Response>): StoreWriteResponse
    interface Stream<Key : Any, Common : Any> {
        @ExperimentalStoreApi
        fun <Response : Any> stream(requestStream: Flow<StoreWriteRequest<Key, Common, Response>>): Flow<StoreWriteResponse>
    }
}
