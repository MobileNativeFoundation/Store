package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.Flow

interface Write<Key : Any, Output : Any> {
    @ExperimentalStoreApi
    suspend fun <Response : Any> write(request: StoreWriteRequest<Key, Output, Response>): StoreWriteResponse
    interface Stream<Key : Any, Output : Any> {
        @ExperimentalStoreApi
        fun <Response : Any> stream(requestStream: Flow<StoreWriteRequest<Key, Output, Response>>): Flow<StoreWriteResponse>
    }
}
