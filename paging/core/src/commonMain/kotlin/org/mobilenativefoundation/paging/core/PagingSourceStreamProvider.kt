package org.mobilenativefoundation.paging.core

import kotlinx.coroutines.flow.Flow

/**
 * Represents a provider of [PagingSource.LoadResult] streams.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 */
interface PagingSourceStreamProvider<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any> {
    /**
     * Provides a flow of [PagingSource.LoadResult] instances for the specified [PagingSource.LoadParams].
     *
     * @param params The [PagingSource.LoadParams] for which to provide the load result stream.
     * @return A flow of [PagingSource.LoadResult] instances representing the load results.
     */
    fun provide(params: PagingSource.LoadParams<K, P>): Flow<PagingSource.LoadResult<Id, K, P, D, E>>
}
