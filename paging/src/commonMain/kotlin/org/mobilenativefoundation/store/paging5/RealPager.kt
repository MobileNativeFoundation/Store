@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import org.mobilenativefoundation.store.store5.StoreReadResponse

/**
 * An internal class that implements the [Pager] interface.
 * It manages the paging of data items based on the given [StoreKey].
 * It also synchronizes updates to single items and collections.
 *
 * @param Id The type of the identifier that uniquely identifies data items.
 * @param SK The subtype of [StoreKey.Single] that represents keys for single items.
 * @param K The type of [StoreKey] used by the Store this pager delegates to.
 * @param SO The subtype of [StoreData.Single] representing the data model for single items.
 * @param O The type of [StoreData] representing the output of the Store this pager delegates to.
 * @param scope The [CoroutineScope] within which the pager operates. Used to launch coroutines for data streaming and joining.
 * @param streamer A [Streamer] function type that provides a flow of [StoreReadResponse] for a given key.
 * @param joiner A [Joiner] function type that combines multiple paging data into a single [StateFlow].
 * @param keyFactory A [KeyFactory] to create new [StoreKey] instances for single items based on their identifiers.
 */
@ExperimentalStoreApi
internal class RealPager<Id : Any, SK : StoreKey.Single<Id>, K : StoreKey<Id>, SO : StoreData.Single<Id>, O : StoreData<Id>>(
    private val scope: CoroutineScope,
    private val streamer: Streamer<Id, K, O>,
    private val joiner: Joiner<Id, K, SO>,
    private val keyFactory: KeyFactory<Id, SK>
) : Pager<Id, K, SO> {

    // StateFlow to emit updates of PagingData.
    private val mutableStateFlow = MutableStateFlow(emptyPagingData())
    override val state: StateFlow<PagingData<Id, SO>> = mutableStateFlow.asStateFlow()

    // Maps to keep track of all PagingData and corresponding streams.
    private val allPagingData: MutableMap<K, PagingData<Id, SO>> = mutableMapOf()
    private val allStreams: MutableMap<K, Job> = mutableMapOf()

    // Mutexes for thread-safe access to maps.
    private val mutexForAllPagingData = Mutex()
    private val mutexForAllStreams = Mutex()

    override fun load(key: K) {
        if (key !is StoreKey.Collection<*>) {
            throw IllegalArgumentException("Invalid key type.")
        }

        // Creating a child scope for coroutines.
        val childScope = scope + Job()

        // Launching a coroutine within the child scope for data loading.
        childScope.launch {
            // Locking the streams map to check and manage stream jobs.
            mutexForAllStreams.withLock {
                // Checking if there's no active stream for the key.
                if (allStreams[key]?.isActive != true) {
                    // Initializing the PagingData for the key.
                    allPagingData[key] = emptyPagingData()

                    val childrenKeys = mutableListOf<K>()

                    // Main job to stream data for the key.
                    val mainJob = launch {
                        streamer(key).collect { response ->
                            if (response is StoreReadResponse.Data<O>) {
                                // Handling collection data response.
                                (response as? StoreReadResponse.Data<StoreData.Collection<Id, SO>>)?.let { dataWithCollection ->

                                    // Updating paging data and state flow inside a locked block for thread safety.
                                    mutexForAllPagingData.withLock {
                                        allPagingData[key] = pagingDataFrom(dataWithCollection.value.items)
                                        val joinedData = joiner(allPagingData)
                                        mutableStateFlow.value = joinedData
                                    }

                                    // For each item in the collection, initiate streaming and handling of single data.
                                    dataWithCollection.value.items.forEach { single ->

                                        val childKey = keyFactory.createFor(single.id)

                                        (childKey as? K)?.let {
                                            // Launching a coroutine for each single item.
                                            val childJob = launch {
                                                initStreamAndHandleSingle(single, childKey, key)
                                            }

                                            // Keeping track of child keys and jobs.
                                            childrenKeys.add(childKey)
                                            mutexForAllStreams.withLock {
                                                allStreams[childKey] = childJob
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Storing the main job and handling its completion.
                    allStreams[key] = mainJob

                    mainJob.invokeOnCompletion {
                        // On completion, cancel and remove all child streams and the main stream.
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

    // Handles streaming and updating of single item data within a collection.
    private suspend fun initStreamAndHandleSingle(single: SO, childKey: K, parentKey: K) {
        streamer(childKey).collect { response ->
            if (response is StoreReadResponse.Data<O>) {
                (response as? StoreReadResponse.Data<SO>)?.let { dataWithSingle ->
                    mutexForAllPagingData.withLock {
                        allPagingData[parentKey]?.items?.let { items ->
                            // Finding and updating the single item within the parent collection.
                            val indexOfSingle = items.indexOfFirst { it.id == single.id }
                            val updatedItems = items.toMutableList()
                            if (updatedItems[indexOfSingle] != dataWithSingle.value) {
                                updatedItems[indexOfSingle] = dataWithSingle.value

                                // Creating and updating the paging data with the updated item list.
                                val updatedPagingData = allPagingData[parentKey]!!.copy(updatedItems)
                                allPagingData[parentKey] = updatedPagingData

                                // Updating the state flow with the joined data.
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



