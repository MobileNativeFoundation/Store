package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.Flow

interface Read {
    interface Stream<Key : Any, Common : Any> {
        /**
         * Return a flow for the given key
         * @param request - see [StoreReadRequest] for configurations
         */
        fun stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<Common>>
    }

    interface StreamWithConflictResolution<Key : Any, Common : Any> {
        fun <Response : Any> stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<Common>>
    }
}
