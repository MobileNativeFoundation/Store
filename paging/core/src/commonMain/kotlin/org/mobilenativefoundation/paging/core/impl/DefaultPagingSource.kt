package org.mobilenativefoundation.paging.core.impl

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.paging.core.PagingKey
import org.mobilenativefoundation.paging.core.PagingSource
import org.mobilenativefoundation.paging.core.PagingSourceStreamProvider

class DefaultPagingSource<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any>(
    private val streamProvider: PagingSourceStreamProvider<Id, K, P, D, E>
) : PagingSource<Id, K, P, D, E> {
    private val streams = mutableMapOf<PagingKey<K, P>, Flow<PagingSource.LoadResult<Id, K, P, D, E>>>()

    override fun stream(params: PagingSource.LoadParams<K, P>): Flow<PagingSource.LoadResult<Id, K, P, D, E>> {
        if (params.key !in streams) {
            streams[params.key] = streamProvider.provide(params)
        }
        return streams[params.key]!!
    }
}