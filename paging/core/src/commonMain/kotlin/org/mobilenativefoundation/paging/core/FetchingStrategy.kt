package org.mobilenativefoundation.paging.core

/**
 * Represents a strategy for determining whether to fetch more data based on the current state of the pager.
 * The fetching strategy is responsible for deciding whether to fetch more data based on the anchor position,
 * prefetch position, paging configuration, and the current state of the paging buffer.
 *
 * Implementing a custom [FetchingStrategy] allows you to define your own logic for when to fetch more data.
 * For example, you can fetch more data when the user scrolls near the end of the currently loaded data, or when a certain number of items are remaining in the buffer.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 */
interface FetchingStrategy<Id : Comparable<Id>, K : Any, P : Any, D : Any> {

    /**
     * Determines whether to fetch more data based on the current state of the pager.
     * The [shouldFetch] implementation should determine whether more data should be fetched based on the provided parameters.
     *
     * @param anchorPosition The current anchor position in the paged data.
     * @param prefetchPosition The position to prefetch data from, or `null` if no prefetching is needed.
     * @param pagingConfig The configuration of the pager, including page size and prefetch distance.
     * @param pagingBuffer The current state of the paging buffer, containing the loaded data.
     * @return `true` if more data should be fetched, `false` otherwise.
     */
    fun shouldFetch(
        anchorPosition: PagingKey<K, P>,
        prefetchPosition: PagingKey<K, P>?,
        pagingConfig: PagingConfig,
        pagingBuffer: PagingBuffer<Id, K, P, D>,
    ): Boolean
}
