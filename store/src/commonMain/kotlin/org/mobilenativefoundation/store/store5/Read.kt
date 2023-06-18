package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.Flow

interface Read {
    interface Stream<Key : Any, Output : Any> {
        /**
         * Return a flow for the given key
         * @param request - see [StoreReadRequest] for configurations
         */
        fun stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<Output>>
    }

    interface StreamWithConflictResolution<Key : Any, Output : Any> {
        fun stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<Output>>
    }
}
