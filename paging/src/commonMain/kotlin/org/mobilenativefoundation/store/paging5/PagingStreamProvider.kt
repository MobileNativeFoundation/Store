package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
fun interface PagingStreamProvider<Id : Any, CK : StoreKey.Collection<Id>> {
    fun stream(params: PagingSource.LoadParams<Id, CK>): Flow<PagingSource.LoadResult>
}