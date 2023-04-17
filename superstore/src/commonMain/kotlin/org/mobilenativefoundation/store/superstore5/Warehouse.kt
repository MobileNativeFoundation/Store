package org.mobilenativefoundation.store.superstore5

/**
 * Represents a fallback data source.
 */
interface Warehouse<Key : Any, out Output : Any> {
    val name: String
    suspend fun get(key: Key): WarehouseResponse<Output>
}
