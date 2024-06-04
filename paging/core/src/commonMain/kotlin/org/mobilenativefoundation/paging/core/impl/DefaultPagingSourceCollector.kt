package org.mobilenativefoundation.paging.core.impl

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.paging.core.PagingAction
import org.mobilenativefoundation.paging.core.PagingSource
import org.mobilenativefoundation.paging.core.PagingSourceCollector
import org.mobilenativefoundation.paging.core.PagingState

class DefaultPagingSourceCollector<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any> : PagingSourceCollector<Id, K, P, D, E, A> {
    override suspend fun invoke(
        params: PagingSource.LoadParams<K, P>,
        results: Flow<PagingSource.LoadResult<Id, K, P, D, E>>,
        state: PagingState<Id, K, P, D, E>,
        dispatch: (action: PagingAction<Id, K, P, D, E, A>) -> Unit
    ) {
        results.collect { result ->
            when (result) {
                is PagingSource.LoadResult.Data -> dispatch(PagingAction.UpdateData(params, result))
                is PagingSource.LoadResult.Error -> dispatch(PagingAction.UpdateError(params, result))
            }
        }
    }
}