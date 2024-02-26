package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
data class PagingState<Id : Any, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>>(
    val anchorPosition: Id?,
    val prefetchPosition: Id?,
    val config: PagingConfig,
    val pages: LinkedHashMap<PagingSource.LoadParams<Id, CK>, PagingSource.LoadResult.Page<Id, CK, SO>>
)