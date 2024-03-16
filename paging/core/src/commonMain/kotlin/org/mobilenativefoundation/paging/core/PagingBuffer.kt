package org.mobilenativefoundation.paging.core

/**
 * A custom data structure for efficiently storing and retrieving paging data.
 *
 * The [PagingBuffer] is responsible for caching and providing access to the loaded pages of data.
 * It allows retrieving data by load parameters, page key, or accessing the entire buffer.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 */
interface PagingBuffer<Id : Comparable<Id>, K : Any, P : Any, D : Any> {
    /**
     * Retrieves the data associated with the specified [PagingSource.LoadParams].
     *
     * @param params The [PagingSource.LoadParams] to retrieve the data for.
     * @return The [PagingSource.LoadResult.Data] associated with the specified [params], or `null` if not found.
     */
    fun get(params: PagingSource.LoadParams<K, P>): PagingSource.LoadResult.Data<Id, K, P, D>?

    /**
     * Retrieves the data associated with the specified [PagingKey].
     *
     * @param key The [PagingKey] to retrieve the data for.
     * @return The [PagingSource.LoadResult.Data] associated with the specified [key], or `null` if not found.
     */
    fun get(key: PagingKey<K, P>): PagingSource.LoadResult.Data<Id, K, P, D>?

    /**
     * Retrieves the data at the head of the buffer.
     *
     * @return The [PagingSource.LoadResult.Data] at the head of the buffer, or `null` if the buffer is empty.
     */
    fun head(): PagingSource.LoadResult.Data<Id, K, P, D>?

    /**
     * Retrieves all the data in the buffer as a list.
     *
     * @return A list of all the [PagingSource.LoadResult.Data] in the buffer.
     */
    fun getAll(): List<PagingSource.LoadResult.Data<Id, K, P, D>>

    /**
     * Checks if the buffer is empty.
     *
     * @return `true` if the buffer is empty, `false` otherwise.
     */
    fun isEmpty(): Boolean

    /**
     * Returns the index of the data associated with the specified [PagingKey] in the buffer.
     *
     * @param key The [PagingKey] to find the index for.
     * @return The index of the data associated with the specified [key], or -1 if not found.
     */
    fun indexOf(key: PagingKey<K, P>): Int
}
