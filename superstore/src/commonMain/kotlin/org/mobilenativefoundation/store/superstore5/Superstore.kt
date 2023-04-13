package org.mobilenativefoundation.store.superstore5

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store.store5.Store

/**
 * Represents a [Store] with fallback mechanisms.
 */
interface Superstore<Key : Any, Output : Any> {
    fun get(
        key: Key,
        fresh: Boolean = false,
        refresh: Boolean = false
    ): Flow<SuperstoreResponse<Output>>
}