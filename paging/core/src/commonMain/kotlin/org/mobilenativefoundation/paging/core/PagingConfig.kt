package org.mobilenativefoundation.paging.core

/**
 * Represents the configuration for paging behavior.
 *
 * @property pageSize The number of items to load per page.
 * @property prefetchDistance The distance from the edge of the loaded data at which to prefetch more data.
 * @property insertionStrategy The strategy for inserting new data into the paging buffer.
 */
data class PagingConfig(
    val pageSize: Int,
    val prefetchDistance: Int,
    val insertionStrategy: InsertionStrategy
) {
    /**
     * Represents different insertion strategies for adding new data to the paging buffer.
     */
    enum class InsertionStrategy {
        /**
         * Appends new data to the end of the buffer.
         */
        APPEND,

        /**
         * Prepends new data to the beginning of the buffer.
         */
        PREPEND,

        /**
         * Replaces the existing data in the buffer with the new data.
         */
        REPLACE,
    }
}