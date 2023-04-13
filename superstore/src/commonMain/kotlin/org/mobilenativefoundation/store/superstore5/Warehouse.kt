package org.mobilenativefoundation.store.superstore5


/**
 * Represents a fallback data source.
 */
interface Warehouse<Key : Any, Output : Any> {
    suspend fun get(key: Key): Output?
}