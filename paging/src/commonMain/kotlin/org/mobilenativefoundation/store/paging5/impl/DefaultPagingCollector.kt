package org.mobilenativefoundation.store.paging5.impl

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.PagingAction
import org.mobilenativefoundation.store.paging5.PagingCollector
import org.mobilenativefoundation.store.paging5.PagingSource
import org.mobilenativefoundation.store.paging5.PagingState

@Suppress("UNCHECKED_CAST")
@ExperimentalStoreApi
class DefaultPagingCollector<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CE : Any> :
    PagingCollector<Id, CK, SO, CE> {
    override suspend fun invoke(
        params: PagingSource.LoadParams<Id, CK>,
        loadResults: Flow<PagingSource.LoadResult>,
        state: PagingState<Id, CK, SO, CE>,
        dispatch: (action: PagingAction) -> Unit,
    ) {
        loadResults.collect { loadResult ->
            when (loadResult) {
                is PagingSource.LoadResult.Error -> {
                    dispatch(PagingAction.App.UpdateError.Exception(loadResult.throwable, params))
                }

                is PagingSource.LoadResult.Page<*, *, *> -> {
                    dispatch(
                        PagingAction.App.UpdateData(
                            params,
                            loadResult as PagingSource.LoadResult.Page<Id, CK, StoreData.Single<Id>>,
                        ),
                    )
                }
            }
        }
    }
}
