package org.mobilenativefoundation.paging.core

import kotlinx.coroutines.flow.Flow

/**
 * Represents a collector for [PagingSource.LoadResult] objects.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched.
 */
interface PagingSourceCollector<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any> {
    /**
     * Collects the load results from the [PagingSource] and dispatches appropriate [PagingAction] objects.
     *
     * @param params The [PagingSource.LoadParams] associated with the load operation.
     * @param results The flow of [PagingSource.LoadResult] instances representing the load results.
     * @param state The current [PagingState] when collecting the load results.
     * @param dispatch The function to dispatch [PagingAction] instances based on the load results.
     */
    suspend operator fun invoke(
        params: PagingSource.LoadParams<K, P>,
        results: Flow<PagingSource.LoadResult<Id, K, P, D, E>>,
        state: PagingState<Id, K, P, D, E>,
        dispatch: (action: PagingAction<Id, K, P, D, E, A>) -> Unit
    )
}