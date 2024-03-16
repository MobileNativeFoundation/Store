package org.mobilenativefoundation.paging.core

import kotlinx.coroutines.flow.Flow

/**
 * Represents a data source that provides paged data.
 *
 * A [PagingSource] is responsible for loading pages of data from a specific data source,
 * such as a database or a network API. It emits a stream of [LoadResult] instances that
 * represent the loaded data or any errors that occurred during loading.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of custom errors that can occur during loading.
 */
interface PagingSource<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any> {
    /**
     * Returns a flow of [LoadResult] instances for the specified [LoadParams].
     *
     * This function is called by the paging library to load pages of data. It takes the
     * [LoadParams] as input and returns a flow of [LoadResult] instances representing
     * the loaded data or any errors that occurred.
     *
     * @param params The [LoadParams] specifying the page key and refresh state.
     * @return A flow of [LoadResult] instances representing the loaded data or errors.
     */
    fun stream(params: LoadParams<K, P>): Flow<LoadResult<Id, K, P, D, E>>

    /**
     * Represents the parameters for loading a page of data.
     *
     * @param K The type of the key used for paging.
     * @param P The type of the parameters associated with each page of data.
     * @property key The [PagingKey] identifying the page to load.
     * @property refresh Indicates whether to refresh the data or load a new page.
     */
    data class LoadParams<K : Any, P : Any>(
        val key: PagingKey<K, P>,
        val refresh: Boolean,
    )

    /**
     * Represents the result of loading a page of data.
     *
     * @param Id The type of the unique identifier for each item in the paged data.
     * @param K The type of the key used for paging.
     * @param P The type of the parameters associated with each page of data.
     * @param D The type of the data items.
     * @param E The type of custom errors that can occur during loading.
     */
    sealed class LoadResult<Id : Comparable<Id>, out K : Any, out P : Any, out D : Any, out E : Any> {
        sealed class Error<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any> : LoadResult<Id, K, P, D, E>() {
            data class Exception<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any>(val error: Throwable) : Error<Id, K, P, D, E>()
            data class Custom<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any>(val error: E) : Error<Id, K, P, D, E>()
        }

        data class Data<Id : Comparable<Id>, K : Any, P : Any, D : Any>(val collection: PagingData.Collection<Id, K, P, D>) : LoadResult<Id, K, P, D, Nothing>()
    }
}