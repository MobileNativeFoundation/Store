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
import org.mobilenativefoundation.store.core5.KeyFactory
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse

@ExperimentalStoreApi
interface StorePager<Id : Any, Key : StoreKey<Id>, Output : StoreData<Id>> {
    val state: StateFlow<StoreReadResponse<Output>>
    fun load(key: Key)
}

@ExperimentalStoreApi
interface DataJoiner<Id : Any, Key : StoreKey<Id>, Output : StoreData.Collection<Id, Single>, Single : StoreData.Single<Id>> {
    suspend operator fun invoke(
        key: Key,
        data: Map<Key, StoreReadResponse.Data<Output>?>
    ): StoreReadResponse.Data<Output>
}


/**
 * Initializes and returns a [StateFlow] that reflects the state of the [Store], updating by a flow of provided keys.
 * @see [launchPagingStore].
 */
@ExperimentalStoreApi
fun <Id : Any, Key : StoreKey<Id>, Output : StoreData<Id>, SingleKey : StoreKey.Single<Id>, Collection : StoreData.Collection<Id, Single>, Single : StoreData.Single<Id>> MutableStore<Key, Output>.launchPagingStore(
    scope: CoroutineScope,
    keys: Flow<Key>,
    joiner: DataJoiner<Id, Key, Collection, Single>,
    keyFactory: KeyFactory<Id, SingleKey>
): StateFlow<StoreReadResponse<Output>> {
    fun streamer(key: Key): Flow<StoreReadResponse<Output>> {
        println("STREAMING FOR KEY $key")
        return stream<Any>(StoreReadRequest.fresh(key))
    }

    val pager = RealStorePager(scope, ::streamer, joiner, keyFactory)

    val childScope = scope + Job()

    childScope.launch {
        keys.collect { key ->
            pager.load(key)
        }
    }

    return pager.state
}

@ExperimentalStoreApi
class RealStorePager<Id : Any, Key : StoreKey<Id>, SingleKey : StoreKey.Single<Id>, Output : StoreData<Id>, Collection : StoreData.Collection<Id, Single>, Single : StoreData.Single<Id>>(
    private val scope: CoroutineScope,
    private val streamer: (key: Key) -> Flow<StoreReadResponse<Output>>,
    private val joiner: DataJoiner<Id, Key, Collection, Single>,
    private val keyFactory: KeyFactory<Id, SingleKey>
) : StorePager<Id, Key, Output> {
    private val mutableStateFlow = MutableStateFlow<StoreReadResponse<Output>>(StoreReadResponse.Initial)
    override val state: StateFlow<StoreReadResponse<Output>> = mutableStateFlow.asStateFlow()

    private val data: MutableMap<Key, StoreReadResponse.Data<Collection>?> = mutableMapOf()
    private val streams: MutableMap<Key, Job> = mutableMapOf()

    private val dataMutex = Mutex()
    private val streamsMutex = Mutex()

    override fun load(key: Key) {

        println("HITTING0 $key")

        if (key !is StoreKey.Collection<*>) {
            throw IllegalArgumentException("Invalid key type")
        }

        val childScope = scope + Job()

        childScope.launch {
            streamsMutex.withLock {
                if (streams[key]?.isActive != true) {
                    data[key] = null

                    val nestedKeys = mutableListOf<Key>()

                    val job = launch {
                        streamer(key).collect { response ->
                            when (response) {
                                is StoreReadResponse.Data<Output> -> {
                                    println("HITTING1 $response")
                                    (response as? StoreReadResponse.Data<Collection>)?.let {
                                        dataMutex.withLock {
                                            data[key] = it
                                            val joinedData = joiner(key, data)
                                            (joinedData as? StoreReadResponse.Data<Output>)?.let {
                                                mutableStateFlow.emit(it)
                                            }
                                        }


                                        it.value.items.forEach { single ->
                                            // TODO: Start stream for each single
                                            // TODO: When change in single, update paging state
                                            val singleKey = keyFactory.createSingleFor(single.id)
                                            (singleKey as? Key)?.let { k ->

                                                val nestedJob = launch {
                                                    streamer(k).collect { singleResponse ->
                                                        when (singleResponse) {
                                                            is StoreReadResponse.Data<Output> -> {
                                                                println("HITTING NESTED $singleResponse")
                                                                (singleResponse as? StoreReadResponse.Data<Single>)?.let {
                                                                    dataMutex.withLock {
                                                                        data[key]?.value?.items?.let { items ->
                                                                            val index =
                                                                                items.indexOfFirst { it.id == single.id }
                                                                            val updatedItems = items.toMutableList()

                                                                            if (updatedItems[index] != it.value) {
                                                                                println("HITTING FOR ${it.value}")
                                                                                updatedItems[index] = it.value
                                                                                val updatedCollection =
                                                                                    data[key]!!.value.copyWith(updatedItems) as? Collection

                                                                                updatedCollection?.let { collection ->
                                                                                    data[key] = data[key]!!.copy(collection)

                                                                                    val joinedData = joiner(key, data)
                                                                                    (joinedData as? StoreReadResponse.Data<Output>)?.let {
                                                                                        mutableStateFlow.emit(it)
                                                                                    }
                                                                                }
                                                                            }

                                                                        }

                                                                    }
                                                                }
                                                            }

                                                            else -> {}
                                                        }
                                                }


                                                }

                                                streams[k] = nestedJob
                                                nestedKeys.add(k)
                                            }

                                        }
                                    }
                                }

                                else -> {
                                    println("HITTING $response")
                                    mutableStateFlow.emit(response)
                                }
                            }
                        }
                    }

                    streams[key] = job

                    job.invokeOnCompletion {
                        nestedKeys.forEach {
                            streams[it]?.cancel()
                            streams.remove(it)
                        }

                        streams[key]?.cancel()
                        streams.remove(key)
                    }
                }
            }
        }
    }
}





