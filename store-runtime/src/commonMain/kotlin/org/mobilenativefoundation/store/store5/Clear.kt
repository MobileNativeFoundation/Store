package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi

interface Clear {
    interface Key<Key : Any> {
        /**
         * Purge a particular entry from memory and disk cache.
         * Persistent storage will only be cleared if a delete function was passed to
         * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
         */
        suspend fun clear(key: Key)
    }

    interface All {
        /**
         * Purge all entries from memory and disk cache.
         * Persistent storage will only be cleared if a clear function was passed to
         * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
         */
        @ExperimentalStoreApi
        suspend fun clear()
    }
}
