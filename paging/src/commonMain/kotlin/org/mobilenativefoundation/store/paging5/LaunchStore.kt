@file:Suppress("UNCHECKED_CAST")


package org.mobilenativefoundation.store.paging5


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.mobilenativefoundation.store.store5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.impl.extensions.fresh


typealias LoadedCollection<Id> = StoreState.Loaded.Collection<Id, StoreData.Single<Id>, StoreData.Collection<Id, StoreData.Single<Id>>>


/**
 * Initializes and returns a [StateFlow] that reflects the state of the Store, updating by a flow of provided keys.
 * @param scope A [CoroutineScope].
 * @param keys A flow of keys that dictate how the Store should be updated.
 * @param fresh A lambda that invokes [Store.fresh].
 * @return A read-only [StateFlow] reflecting the state of the Store.
 */
private fun <Id : Any, Key : StoreKey<Id>, Output : StoreData<Id>> launchStore(
    scope: CoroutineScope,
    keys: Flow<Key>,
    fresh: suspend (currentState: StoreState<Id, Output>, key: Key) -> StoreState<Id, Output>
): StateFlow<StoreState<Id, Output>> {
    val stateFlow = MutableStateFlow<StoreState<Id, Output>>(StoreState.Loading)

    scope.launch {
        keys.collect { key ->
            val nextState = fresh(stateFlow.value, key)
            stateFlow.emit(nextState)
        }
    }

    return stateFlow.asStateFlow()
}

/**
 * Initializes and returns a [StateFlow] that reflects the state of the [Store], updating by a flow of provided keys.
 * @see [launchStore].
 */
fun <Id : Any, Key : StoreKey<Id>, Output : StoreData<Id>> Store<Key, Output>.launchStore(
    scope: CoroutineScope,
    keys: Flow<Key>,
): StateFlow<StoreState<Id, Output>> {
    return launchStore(scope, keys) { currentState, key ->
        this.freshAndInsertUpdatedItems(currentState, key)
    }
}

/**
 * Initializes and returns a [StateFlow] that reflects the state of the [Store], updating by a flow of provided keys.
 * @see [launchStore].
 */
@OptIn(ExperimentalStoreApi::class)
fun <Id : Any, Key : StoreKey<Id>, Output : StoreData<Id>> MutableStore<Key, Output>.launchStore(
    scope: CoroutineScope,
    keys: Flow<Key>,
): StateFlow<StoreState<Id, Output>> {
    return launchStore(scope, keys) { currentState, key ->
        this.freshAndInsertUpdatedItems(currentState, key)
    }
}


/**
 * Updates the Store's state based on a provided key and a retrieval mechanism.
 * @param currentState The current state of the Store.
 * @param key The key that dictates how the state should be updated.
 * @param get A lambda that defines how to retrieve data from the Store based on a key.
 */
private suspend fun <Id : Any, Key : StoreKey<Id>, Output : StoreData<Id>> freshAndInsertUpdatedItems(
    currentState: StoreState<Id, Output>,
    key: Key,
    get: suspend (key: Key) -> Output,
): StoreState<Id, Output> {
    return try {
        if (key !is StoreKey.Collection<*>) throw IllegalArgumentException("Invalid key type")

        val lastOutput = when (currentState) {
            is StoreState.Loaded.Collection<*, *, *> -> (currentState as LoadedCollection<Id>).data
            else -> null
        }

        val nextOutput = get(key) as StoreData.Collection<Id, StoreData.Single<Id>>

        val output = (lastOutput?.insertItems(key.loadType, nextOutput.items) ?: nextOutput)
        StoreState.Loaded.Collection(output) as StoreState<Id, Output>

    } catch (error: Exception) {
        StoreState.Error.Exception(error)
    }
}

/**
 * Updates the [Store]'s state based on a provided key.
 * @see [freshAndInsertUpdatedItems].
 */
private suspend fun <Id : Any, Key : StoreKey<Id>, Output : StoreData<Id>> Store<Key, Output>.freshAndInsertUpdatedItems(
    currentState: StoreState<Id, Output>,
    key: Key
): StoreState<Id, Output> {
    return freshAndInsertUpdatedItems(
        currentState,
        key
    ) {
        this.fresh(it)
    }
}

/**
 * Updates the [MutableStore]'s state based on a provided key.
 * @see [freshAndInsertUpdatedItems].
 */
@OptIn(ExperimentalStoreApi::class)
private suspend fun <Id : Any, Key : StoreKey<Id>, Output : StoreData<Id>> MutableStore<Key, Output>.freshAndInsertUpdatedItems(
    currentState: StoreState<Id, Output>,
    key: Key
): StoreState<Id, Output> {
    return freshAndInsertUpdatedItems(
        currentState,
        key
    ) {
        this.fresh<Key, Output, Any>(it)
    }
}

