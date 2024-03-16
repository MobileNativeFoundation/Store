package org.mobilenativefoundation.paging.core.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.paging.core.PagingData
import org.mobilenativefoundation.paging.core.PagingKey
import org.mobilenativefoundation.paging.core.PagingSource
import org.mobilenativefoundation.paging.core.PagingSourceStreamProvider
import org.mobilenativefoundation.paging.core.StorePagingSourceKeyFactory
import org.mobilenativefoundation.store.store5.StoreReadResponse

class StorePagingSourceStreamProvider<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any>(
    private val createParentStream: (key: PagingKey<K, P>) -> Flow<PagingSource.LoadResult<Id, K, P, D, E>>,
    private val createChildStream: (key: PagingKey<K, P>) -> Flow<StoreReadResponse<PagingData<Id, K, P, D>>>,
    private val keyFactory: StorePagingSourceKeyFactory<Id, K, P, D>
) : PagingSourceStreamProvider<Id, K, P, D, E> {
    private val pages: MutableMap<PagingKey<K, P>, PagingSource.LoadResult.Data<Id, K, P, D>> = mutableMapOf()
    private val mutexForPages = Mutex()

    override fun provide(params: PagingSource.LoadParams<K, P>): Flow<PagingSource.LoadResult<Id, K, P, D, E>> =
        createParentStream(params.key).map { result ->
            when (result) {
                is PagingSource.LoadResult.Data -> {
                    mutexForPages.withLock {
                        pages[params.key] = result
                    }

                    var data = result

                    result.collection.items.forEach { child ->
                        val childKey = keyFactory.createKeyFor(child)
                        initAndCollectChildStream(child, childKey, params.key) { updatedData -> data = updatedData }
                    }

                    data
                }

                is PagingSource.LoadResult.Error -> result
            }
        }

    private fun initAndCollectChildStream(
        data: PagingData.Single<Id, K, P, D>,
        key: PagingKey<K, P>,
        parentKey: PagingKey<K, P>,
        emit: (updatedData: PagingSource.LoadResult.Data<Id, K, P, D>) -> Unit
    ) {
        createChildStream(key).distinctUntilChanged().onEach { response ->

            if (response is StoreReadResponse.Data) {
                val updatedValue = response.value

                if (updatedValue is PagingData.Single) {
                    mutexForPages.withLock {
                        pages[parentKey]!!.let { currentData ->
                            val updatedItems = currentData.collection.items.toMutableList()
                            val indexOfChild = updatedItems.indexOfFirst { it.id == data.id }
                            val child = updatedItems[indexOfChild]
                            if (child != updatedValue) {
                                updatedItems[indexOfChild] = updatedValue

                                val updatedPage = currentData.copy(collection = currentData.collection.copy(items = updatedItems))

                                pages[parentKey] = updatedPage

                                emit(updatedPage)
                            }
                        }
                    }
                }
            }
        }
    }
}