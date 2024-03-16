package org.mobilenativefoundation.paging.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import org.mobilenativefoundation.paging.core.impl.StorePagingSourceStreamProvider
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse

@OptIn(ExperimentalStoreApi::class)
fun <Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any> MutableStore<PagingKey<K, P>, PagingData<Id, K, P, D>>.pagingSourceStreamProvider(
    keyFactory: StorePagingSourceKeyFactory<Id, K, P, D>
): PagingSourceStreamProvider<Id, K, P, D, E> {

    fun createParentStream(key: PagingKey<K, P>) = paged<Id, K, P, D, E>(key)

    fun createChildStream(key: PagingKey<K, P>) = stream<Any>(StoreReadRequest.fresh(key))

    return StorePagingSourceStreamProvider(::createParentStream, ::createChildStream, keyFactory)
}

fun <Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any> Store<PagingKey<K, P>, PagingData<Id, K, P, D>>.pagingSourceStreamProvider(
    keyFactory: StorePagingSourceKeyFactory<Id, K, P, D>
): PagingSourceStreamProvider<Id, K, P, D, E> {

    fun createParentStream(key: PagingKey<K, P>) = paged<Id, K, P, D, E>(key)

    fun createChildStream(key: PagingKey<K, P>) = stream(StoreReadRequest.fresh(key))

    return StorePagingSourceStreamProvider(::createParentStream, ::createChildStream, keyFactory)
}

@Suppress("UNCHECKED_CAST")
private fun <Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any> handleStoreReadResponse(response: StoreReadResponse<PagingData<Id, K, P, D>>) = when (response) {
    is StoreReadResponse.Data -> PagingSource.LoadResult.Data(response.value as PagingData.Collection)
    is StoreReadResponse.Error.Exception -> PagingSource.LoadResult.Error.Exception(response.error)
    is StoreReadResponse.Error.Message -> PagingSource.LoadResult.Error.Exception(Exception(response.message))

    StoreReadResponse.Initial,
    is StoreReadResponse.Loading,
    is StoreReadResponse.NoNewData -> null

    is StoreReadResponse.Error.Custom<*> -> PagingSource.LoadResult.Error.Custom(response.error as E)
}

@OptIn(ExperimentalStoreApi::class)
fun <Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any> MutableStore<PagingKey<K, P>, PagingData<Id, K, P, D>>.paged(
    key: PagingKey<K, P>
): Flow<PagingSource.LoadResult<Id, K, P, D, E>> = stream<Any>(StoreReadRequest.fresh(key)).mapNotNull { response -> handleStoreReadResponse(response) }

fun <Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any> Store<PagingKey<K, P>, PagingData<Id, K, P, D>>.paged(
    key: PagingKey<K, P>
): Flow<PagingSource.LoadResult<Id, K, P, D, E>> = stream(StoreReadRequest.cached(key, refresh = false)).mapNotNull { response -> handleStoreReadResponse(response) }