package org.mobilenativefoundation.paging.core

/**
 * Represents a strategy for aggregating loaded pages of data into a single instance of [PagingItems].
 *
 * The [AggregatingStrategy] determines how the loaded pages of data should be combined and ordered to form a coherent list of [PagingData.Single] items.
 * It takes into account the anchor position, prefetch position, paging configuration, and the current state of the paging buffer.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 */
interface AggregatingStrategy<Id : Comparable<Id>, K : Any, P : Any, D : Any> {

    /**
     * Aggregates the loaded pages of data into a single instance of [PagingItems].
     *
     * @param anchorPosition The current anchor position in the paged data.
     * @param prefetchPosition The position to prefetch data from, or `null` if no prefetching is needed.
     * @param pagingConfig The configuration of the pager, including page size and prefetch distance.
     * @param pagingBuffer The current state of the paging buffer, containing the loaded data.
     * @return The aggregated list of [PagingItems] representing the combined and ordered paging data.
     */
    fun aggregate(
        anchorPosition: PagingKey<K, P>,
        prefetchPosition: PagingKey<K, P>?,
        pagingConfig: PagingConfig,
        pagingBuffer: PagingBuffer<Id, K, P, D>,
    ): PagingItems<Id, K, P, D>
}