package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
interface PagingSource<Id: Any, CK: StoreKey.Collection<Id>, SO: StoreData.Single<Id>> {
    fun stream(params: LoadParams<Id, CK>): Flow<LoadResult>

    data class LoadParams<Id: Any, CK: StoreKey.Collection<Id>>(
        val key: CK,
        val refresh: Boolean
    )

    sealed class LoadResult {
        data class Error(val throwable: Throwable): LoadResult()
        data class Page<Id: Any, CK: StoreKey.Collection<Id>, SO: StoreData.Single<Id>>(
            val data: List<SO>,
            val itemsAfter: Int?,
            val itemsBefore: Int?,
            val nextKey: CK?,
            val prevKey: CK?,
        ): LoadResult()
    }
}


