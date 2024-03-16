package org.mobilenativefoundation.paging.core

/**
 * Represents a mutable version of [PagingBuffer] that allows adding and updating paging data.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 */
interface MutablePagingBuffer<Id : Comparable<Id>, K : Any, P : Any, D : Any> : PagingBuffer<Id, K, P, D> {
    /**
     * Puts the loaded page of data associated with the specified [PagingSource.LoadParams] into the buffer.
     *
     * @param params The [PagingSource.LoadParams] associated with the loaded page.
     * @param page The [PagingSource.LoadResult.Data] representing the loaded page of data.
     */
    fun put(params: PagingSource.LoadParams<K, P>, page: PagingSource.LoadResult.Data<Id, K, P, D>)
}