@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.store5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse

private class StopProcessingException : Exception()

/**
 * Initializes and returns a [StateFlow] that reflects the state of the Store, updating by a flow of provided keys.
 * @param scope A [CoroutineScope].
 * @param keys A flow of keys that dictate how the Store should be updated.
 * @param stream A lambda that invokes [Store.stream].
 * @return A read-only [StateFlow] reflecting the state of the Store.
 */
private fun <Id : Any, Key : StoreKey<Id>, Output : StoreData<Id>> launchPagingStore(
    scope: CoroutineScope,
    keys: Flow<Key>,
    stream: (key: Key) -> Flow<StoreReadResponse<Output>>,
): StateFlow<StoreReadResponse<Output>> {
    val stateFlow = MutableStateFlow<StoreReadResponse<Output>>(StoreReadResponse.Initial)

    scope.launch {

        try {
            val firstKey = keys.first()
            if (firstKey !is StoreKey.Collection<*>) throw IllegalArgumentException("Invalid key type")

            stream(firstKey).collect { response ->
                if (response is StoreReadResponse.Data<Output>) {
                    val joinedDataResponse = joinData(firstKey, stateFlow.value, response)
                    stateFlow.emit(joinedDataResponse)
                } else {
                    stateFlow.emit(response)
                }

                if (response is StoreReadResponse.Data<Output> ||
                    response is StoreReadResponse.Error ||
                    response is StoreReadResponse.NoNewData
                ) {
                    throw StopProcessingException()
                }
            }
        } catch (_: StopProcessingException) {
        }

        keys.drop(1).collect { key ->
            if (key !is StoreKey.Collection<*>) throw IllegalArgumentException("Invalid key type")
            val firstDataResponse = stream(key).first { it.dataOrNull() != null } as StoreReadResponse.Data<Output>
            val joinedDataResponse = joinData(key, stateFlow.value, firstDataResponse)
            stateFlow.emit(joinedDataResponse)
        }
    }

    return stateFlow.asStateFlow()
}

/**
 * Initializes and returns a [StateFlow] that reflects the state of the [Store], updating by a flow of provided keys.
 * @see [launchPagingStore].
 */
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
@OptIn(ExperimentalStoreApi::class)
fun <Id : Any, Key : StoreKey<Id>, Output : StoreData<Id>> MutableStore<Key, Output>.launchPagingStore(
    scope: CoroutineScope,
    keys: Flow<Key>,
): StateFlow<StoreReadResponse<Output>> {
    return launchPagingStore(scope, keys) { key ->
        this.stream<Any>(StoreReadRequest.fresh(key))
    }
}

private fun <Id : Any, Key : StoreKey.Collection<Id>, Output : StoreData<Id>> joinData(
    key: Key,
    prevResponse: StoreReadResponse<Output>,
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
