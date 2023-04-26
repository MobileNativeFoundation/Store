package org.mobilenativefoundation.store.store5

/**
 * Represents a fallback data source.
 */
interface Fallback<Key : Any, out Output : Any> {
    val name: String
    suspend fun get(key: Key): FallbackResponse<Output>
}

sealed class FallbackResponse<out Output : Any> {
    data class Data<Output : Any>(
        val data: Output,
        val origin: String
    ) : FallbackResponse<Output>()

    object Empty : FallbackResponse<Nothing>()
}
