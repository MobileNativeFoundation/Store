package org.mobilenativefoundation.store.paging5.impl

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.PagingSource
import org.mobilenativefoundation.store.paging5.PagingStreamProvider

@ExperimentalStoreApi
class DefaultPagingSource<Id : Any, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>>(
    private val streamProvider: PagingStreamProvider<Id, CK>,
) : PagingSource<Id, CK, SO> {

    private val streams: MutableMap<CK, Flow<PagingSource.LoadResult>> = mutableMapOf()

    override fun stream(params: PagingSource.LoadParams<Id, CK>): Flow<PagingSource.LoadResult> {
        if (params.key in streams) return streams[params.key]!!
        val stream = streamProvider.stream(params)
        streams[params.key] = stream
        return stream
    }
}
