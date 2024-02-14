@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse

/**
 * Initializes and returns a [StateFlow] that reflects the state of the Store, updating by a flow of provided keys.
 * @param scope A [CoroutineScope].
 * @param keys A flow of keys that dictate how the Store should be updated.
 * @param stream A lambda that invokes [Store.stream].
 * @return A read-only [StateFlow] reflecting the state of the Store.
 */
@ExperimentalStoreApi
private fun <Id : Any, Key : StoreKey<Id>, Output : StoreData<Id>> launchPagingStore(
    scope: CoroutineScope,
    keys: Flow<Key>,
    stream: (key: Key) -> Flow<StoreReadResponse<Output>>,
): StateFlow<StoreReadResponse<Output>> {
    val childScope = scope + Job()

    val prevData = MutableStateFlow<StoreReadResponse.Data<Output>?>(null)
    val stateFlow = MutableStateFlow<StoreReadResponse<Output>>(StoreReadResponse.Initial)
    val activeStreams = mutableMapOf<Key, Job>()

    childScope.launch {
        keys.collect { key ->
            if (key !is StoreKey.Collection<*>) {
                throw IllegalArgumentException("Invalid key type")
            }

            if (activeStreams[key]?.isActive != true) {
                val job = this.launch {
                    stream(key).collect { response ->
                        when (response) {
                            is StoreReadResponse.Data<Output> -> {
                                val joinedDataResponse = joinData(key, prevData.value, response)
                                prevData.emit(joinedDataResponse)
                                stateFlow.emit(joinedDataResponse)
                            }

                            else -> {
                                stateFlow.emit(response)
                            }
                        }
                    }
                }

                activeStreams[key] = job

                job.invokeOnCompletion {
                    activeStreams[key]?.cancel()
                    activeStreams.remove(key)
                }
            }
        }
    }

    scope.coroutineContext[Job]?.invokeOnCompletion {
        childScope.cancel()
    }

    return stateFlow.asStateFlow()
}

/**
 * Initializes and returns a [StateFlow] that reflects the state of the [Store], updating by a flow of provided keys.
 * @see [launchPagingStore].
 */
@ExperimentalStoreApi
fun <Id : Any, Key : StoreKey<Id>, Output : StoreData<Id>> Store<Key, Output>.launchPagingStore(
    scope: CoroutineScope,
    keys: Flow<Key>,
): StateFlow<StoreReadResponse<Output>> {
    return launchPagingStore(scope, keys) { key ->
        this.stream(StoreReadRequest.fresh(key))
    }
}

/**
 * Initializes and returns a [StateFlow] that reflects the state of the [Store], updating by a flow of provided keys.
 * @see [launchPagingStore].
 */
@ExperimentalStoreApi
fun <Id : Any, Key : StoreKey<Id>, Output : StoreData<Id>> MutableStore<Key, Output>.launchPagingStore(
    scope: CoroutineScope,
    keys: Flow<Key>,
): StateFlow<StoreReadResponse<Output>> {
    return launchPagingStore(scope, keys) { key ->
        this.stream<Any>(StoreReadRequest.fresh(key))
    }
}

@ExperimentalStoreApi
private fun <Id : Any, Key : StoreKey.Collection<Id>, Output : StoreData<Id>> joinData(
    key: Key,
    prevResponse: StoreReadResponse.Data<Output>?,
    currentResponse: StoreReadResponse.Data<Output>
): StoreReadResponse.Data<Output> {
    val lastOutput = when (prevResponse) {
        is StoreReadResponse.Data<Output> -> prevResponse.value as? StoreData.Collection<Id, StoreData.Single<Id>>
        else -> null
    }
    val currentData = currentResponse.value as StoreData.Collection<Id, StoreData.Single<Id>>
    val joinedOutput = (lastOutput?.insertItems(key.insertionStrategy, currentData.items) ?: currentData) as Output
    return StoreReadResponse.Data(joinedOutput, currentResponse.origin)
}
