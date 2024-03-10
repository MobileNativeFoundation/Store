package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
interface PagingCollector<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CE : Any> {
    suspend operator fun invoke(
        params: PagingSource.LoadParams<Id, CK>,
        loadResults: Flow<PagingSource.LoadResult>,
        state: PagingState<Id, CK, SO, CE>,
        dispatch: (action: PagingAction) -> Unit,
    )
}
