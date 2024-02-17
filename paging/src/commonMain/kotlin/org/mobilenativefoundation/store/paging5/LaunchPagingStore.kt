@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse


@ExperimentalStoreApi
data class PagingData<Id : Any, SO : StoreData.Single<Id>>(
    val items: List<SO>
)

@ExperimentalStoreApi
interface Pager<Id : Any, K : StoreKey<Id>, SO : StoreData.Single<Id>> {
    val state: StateFlow<PagingData<Id, SO>>
    fun load(key: K)

    companion object {
        fun <Id : Any, SK : StoreKey.Single<Id>, K : StoreKey<Id>, SO : StoreData.Single<Id>, O : StoreData<Id>> create(
            scope: CoroutineScope,
            store: Store<K, O>,
            joiner: Joiner<Id, K, SO>,
            keyFactory: KeyFactory<Id, SK>
        ): Pager<Id, K, SO> {

            val streamer = object : Streamer<Id, K, O> {
                override fun invoke(key: K): Flow<StoreReadResponse<O>> {
                    return store.stream(StoreReadRequest.fresh(key))
                }
            }

            return RealPager(
                scope,
                streamer,
                joiner,
                keyFactory
            )
        }

        fun <Id : Any, SK : StoreKey.Single<Id>, K : StoreKey<Id>, SO : StoreData.Single<Id>, O : StoreData<Id>> create(
            scope: CoroutineScope,
            store: MutableStore<K, O>,
            joiner: Joiner<Id, K, SO>,
            keyFactory: KeyFactory<Id, SK>
        ): Pager<Id, K, SO> {

            val streamer = object : Streamer<Id, K, O> {
                override fun invoke(key: K): Flow<StoreReadResponse<O>> {
                    return store.stream<Any>(StoreReadRequest.fresh(key))
                }
            }

            return RealPager(
                scope,
                streamer,
                joiner,
                keyFactory
            )
        }
    }
}

@ExperimentalStoreApi
interface Joiner<Id : Any, K : StoreKey<Id>, SO : StoreData.Single<Id>> {
    suspend operator fun invoke(data: Map<K, PagingData<Id, SO>>): PagingData<Id, SO>
}

@ExperimentalStoreApi
interface Streamer<Id : Any, K : StoreKey<Id>, O : StoreData<Id>> {
    operator fun invoke(key: K): Flow<StoreReadResponse<O>>
}

@ExperimentalStoreApi
interface KeyFactory<Id : Any, SK : StoreKey.Single<Id>> {
    fun createFor(id: Id): SK
}

@ExperimentalStoreApi
class RealPager<Id : Any, SK : StoreKey.Single<Id>, K : StoreKey<Id>, SO : StoreData.Single<Id>, O : StoreData<Id>>(
    private val scope: CoroutineScope,
    private val streamer: Streamer<Id, K, O>,
    private val joiner: Joiner<Id, K, SO>,
    private val keyFactory: KeyFactory<Id, SK>
) : Pager<Id, K, SO> {

    private val mutableStateFlow = MutableStateFlow(emptyPagingData())
    override val state: StateFlow<PagingData<Id, SO>> = mutableStateFlow.asStateFlow()

    private val allPagingData: MutableMap<K, PagingData<Id, SO>> = mutableMapOf()
    private val allStreams: MutableMap<K, Job> = mutableMapOf()

    private val mutexForAllPagingData = Mutex()
    private val mutexForAllStreams = Mutex()
    override fun load(key: K) {
        if (key !is StoreKey.Collection<*>) {
            throw IllegalArgumentException("Invalid key type.")
        }

        val childScope = scope + Job()

        childScope.launch {
            mutexForAllStreams.withLock {
                if (allStreams[key]?.isActive != true) {
                    allPagingData[key] = emptyPagingData()

                    val childrenKeys = mutableListOf<K>()

                    val mainJob = launch {
                        streamer(key).collect { response ->
                            if (response is StoreReadResponse.Data<O>) {
                                (response as? StoreReadResponse.Data<StoreData.Collection<Id, SO>>)?.let { dataWithCollection ->

                                    mutexForAllPagingData.withLock {
                                        allPagingData[key] = pagingDataFrom(dataWithCollection.value.items)
                                        val joinedData = joiner(allPagingData)
                                        mutableStateFlow.value = joinedData
                                    }

                                    dataWithCollection.value.items.forEach { single ->

                                        val childKey = keyFactory.createFor(single.id)

                                        (childKey as? K)?.let {
                                            val childJob = launch {
                                                initStreamAndHandleSingle(single, childKey, key)
                                            }

                                            childrenKeys.add(childKey)

                                            // TODO: This might result in a deadlock
                                            mutexForAllStreams.withLock {
                                                allStreams[childKey] = childJob
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    allStreams[key] = mainJob

                    mainJob.invokeOnCompletion {
                        childrenKeys.forEach { childKey ->
                            allStreams[childKey]?.cancel()
                            allStreams.remove(childKey)
                        }

                        allStreams[key]?.cancel()
                        allStreams.remove(key)
                    }
                }
            }
        }
    }

    private suspend fun initStreamAndHandleSingle(single: SO, childKey: K, parentKey: K) {
        streamer(childKey).collect { response ->
            if (response is StoreReadResponse.Data<O>) {
                (response as? StoreReadResponse.Data<SO>)?.let { dataWithSingle ->
                    mutexForAllPagingData.withLock {
                        allPagingData[parentKey]?.items?.let { items ->
                            val indexOfSingle = items.indexOfFirst { it.id == single.id }
                            val updatedItems = items.toMutableList()
                            if (updatedItems[indexOfSingle] != dataWithSingle.value) {
                                updatedItems[indexOfSingle] = dataWithSingle.value

                                val updatedPagingData = allPagingData[parentKey]!!.copy(updatedItems)
                                allPagingData[parentKey] = updatedPagingData

                                val joinedData = joiner(allPagingData)
                                mutableStateFlow.value = joinedData
                            }
                        }
                    }

                }
            }
        }
    }

    private fun emptyPagingData() = PagingData<Id, SO>(emptyList())
    private fun pagingDataFrom(items: List<SO>) = PagingData(items)

}



