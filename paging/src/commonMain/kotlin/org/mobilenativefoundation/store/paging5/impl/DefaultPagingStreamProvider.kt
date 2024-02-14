package org.mobilenativefoundation.store.paging5.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.PagingKeyFactory
import org.mobilenativefoundation.store.paging5.PagingSource
import org.mobilenativefoundation.store.paging5.PagingStreamProvider
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.impl.extensions.PagingResult
import org.mobilenativefoundation.store.store5.impl.extensions.paged

@Suppress("UNCHECKED_CAST")
@ExperimentalStoreApi
fun <Id : Any, K : StoreKey<Id>, O : StoreData<Id>, SK : StoreKey.Single<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> MutableStore<K, O>.defaultPagingStreamProvider(
    keyFactory: PagingKeyFactory<Id, SK, SO>
): PagingStreamProvider<Id, CK> {
    fun parentPager(key: CK) = paged(key)

    fun childStreamer(key: SK): Flow<StoreReadResponse<O>> =
        stream<Any>(StoreReadRequest.cached(key as K, refresh = false))

    val delegate = DelegatePagingStreamProvider(::parentPager, ::childStreamer, keyFactory)
    return DefaultPagingStreamProvider(delegate)
}

@Suppress("UNCHECKED_CAST")
@ExperimentalStoreApi
fun <Id : Any, K : StoreKey<Id>, O : StoreData<Id>, SK : StoreKey.Single<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> Store<K, O>.defaultPagingStreamProvider(
    keyFactory: PagingKeyFactory<Id, SK, SO>
): PagingStreamProvider<Id, CK> {
    fun parentPager(key: CK) = paged(key)

    fun childStreamer(key: SK): Flow<StoreReadResponse<O>> =
        stream(StoreReadRequest.cached(key as K, refresh = false))

    val delegate = DelegatePagingStreamProvider(::parentPager, ::childStreamer, keyFactory)
    return DefaultPagingStreamProvider(delegate)
}

@Suppress("UNCHECKED_CAST")
@ExperimentalStoreApi
internal class DelegatePagingStreamProvider<Id : Any, O : StoreData<Id>, SK : StoreKey.Single<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CO : StoreData.Collection<Id, CK, SO>>(
    private val parentPager: (key: CK) -> Flow<PagingResult>,
    private val childStreamer: (key: SK) -> Flow<StoreReadResponse<O>>,
    private val keyFactory: PagingKeyFactory<Id, SK, SO>
) {

    private val pages: MutableMap<CK, PagingSource.LoadResult.Page<Id, CK, SO>> = mutableMapOf()
    private val mutexForPages = Mutex()

    fun page(params: PagingSource.LoadParams<Id, CK>) = parentPager(params.key).map { pagingResult ->
        when (pagingResult) {
            is PagingResult.Data<*, *, *, *> -> {
                val co = pagingResult.value as CO
                val items = co.items
                val page = PagingSource.LoadResult.Page(
                    items,
                    co.itemsAfter,
                    co.itemsBefore,
                    co.nextKey,
                    params.key
                )

                mutexForPages.withLock {
                    pages[params.key] = page
                }

                var liveData = page

                pagingResult.value.items.forEach { single ->
                    val childKey = keyFactory.createKeyFor(single as SO)
                    initStreamAndHandleSingle(single, childKey, params.key) { updatedData ->
                        liveData = updatedData
                    }
                }

                liveData
            }

            is PagingResult.Error -> PagingSource.LoadResult.Error(pagingResult.throwable)
        }
    }

    private fun initStreamAndHandleSingle(
        single: SO,
        childKey: SK,
        parentKey: CK,
        emitInParentStream: (updatedData: PagingSource.LoadResult.Page<Id, CK, SO>) -> Unit
    ) {
        childStreamer(childKey).distinctUntilChanged().onEach { response ->
            if (response is StoreReadResponse.Data) {
                (response as? StoreReadResponse.Data<SO>)?.let { postData: StoreReadResponse.Data<SO> ->
                    mutexForPages.withLock {
                        pages[parentKey]?.let { currentPage ->
                            val updatedItems = currentPage.data.toMutableList()
                            val indexOfSingle = updatedItems.indexOfFirst { it.id == single.id }
                            if (updatedItems[indexOfSingle] != postData.value) {
                                updatedItems[indexOfSingle] = postData.value

                                val updatedPagingData = currentPage.copy(updatedItems)
                                pages[parentKey] = updatedPagingData

                                emitInParentStream(updatedPagingData)
                            }
                        }
                    }
                }
            }
        }
    }
}

@ExperimentalStoreApi
internal class DefaultPagingStreamProvider<Id : Any, CK : StoreKey.Collection<Id>, SK : StoreKey.Single<Id>, O : StoreData<Id>, SO : StoreData.Single<Id>, CO : StoreData.Collection<Id, CK, SO>>(
    private val delegate: DelegatePagingStreamProvider<Id, O, SK, CK, SO, CO>
) : PagingStreamProvider<Id, CK> {
    override fun stream(params: PagingSource.LoadParams<Id, CK>): Flow<PagingSource.LoadResult> = delegate.page(params)
}
