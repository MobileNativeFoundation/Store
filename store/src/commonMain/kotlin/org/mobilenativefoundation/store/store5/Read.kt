package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.Flow

interface Read {
    interface Stream<Key : Any, CommonRepresentation : Any> {
        /**
         * Return a flow for the given key
         * @param request - see [StoreReadRequest] for configurations
         */
        fun stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<CommonRepresentation>>
    }

    interface StreamWithConflictResolution<Key : Any, CommonRepresentation : Any> {
        fun <NetworkWriteResponse : Any> stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<CommonRepresentation>>
    }
}
