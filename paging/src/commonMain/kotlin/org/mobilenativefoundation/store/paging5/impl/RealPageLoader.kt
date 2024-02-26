package org.mobilenativefoundation.store.paging5.impl

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.PagingSource

@Suppress("UNCHECKED_CAST")
@ExperimentalStoreApi
internal class RealPageLoader<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>>(
    private val pagingSource: PagingSource<Id, CK, SO>,
    private val onPageLoaded: (params: PagingSource.LoadParams<Id, CK>, page: PagingSource.LoadResult.Page<Id, CK, SO>) -> Unit,
    private val onLoadError: (error: Throwable) -> Unit
) : PageLoader<Id, CK, SO> {
    override suspend fun loadPage(params: PagingSource.LoadParams<Id, CK>) {
        pagingSource.stream(params).collect { loadResult ->
            when (loadResult) {
                is PagingSource.LoadResult.Error -> onLoadError(loadResult.throwable)
                is PagingSource.LoadResult.Page<*, *, *> -> onPageLoaded(
                    params,
                    loadResult as PagingSource.LoadResult.Page<Id, CK, SO>
                )
            }
        }
    }

}