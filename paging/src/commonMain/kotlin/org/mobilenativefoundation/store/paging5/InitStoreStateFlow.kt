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


/**
 * Initializes and returns a [StateFlow] that reflects the state of the Store, updating by a flow of provided keys.
 * @param scope A [CoroutineScope].
 * @param keys A flow of keys that dictate how the Store should be updated.
 * @param updateStoreState A lambda that defines how the Store's state should be updated based on the current state and a key.
 * @return A read-only [StateFlow] reflecting the state of the Store.
 */
private fun <Id : Any, Key : StoreKey<Id>, Output : Identifiable<Id>> initStoreStateFlow(
    scope: CoroutineScope,
    keys: Flow<Key>,
    updateStoreState: suspend (currentState: StoreState<Id, Output>, key: Key) -> StoreState<Id, Output>
): StateFlow<StoreState<Id, Output>> {
    val stateFlow = MutableStateFlow<StoreState<Id, Output>>(StoreState.Loading)

    scope.launch {
        keys.collect { key ->
            println("KEY = $key")
            println("CURRENT STATE = ${stateFlow.value}")
            val updatedState = updateStoreState(stateFlow.value, key)
            stateFlow.emit(updatedState)
        }
    }

    return stateFlow.asStateFlow()
}

/**
 * Initializes and returns a [StateFlow] that reflects the state of the [Store], updating by a flow of provided keys.
 * @see [initStoreStateFlow].
 */
fun <Id : Any, Key : StoreKey<Id>, Output : Identifiable<Id>> Store<Key, Output>.initStoreStateFlow(
    scope: CoroutineScope,
    keys: Flow<Key>,
): StateFlow<StoreState<Id, Output>> {
    return initStoreStateFlow(scope, keys) { currentState, key ->
        this.updateStoreState(currentState, key)
    }
}

/**
 * Initializes and returns a [StateFlow] that reflects the state of the [Store], updating by a flow of provided keys.
 * @see [initStoreStateFlow].
 */
@OptIn(ExperimentalStoreApi::class)
fun <Id : Any, Key : StoreKey<Id>, Output : Identifiable<Id>> MutableStore<Key, Output>.initStoreStateFlow(
    scope: CoroutineScope,
    keys: Flow<Key>,
): StateFlow<StoreState<Id, Output>> {
    return initStoreStateFlow(scope, keys) { currentState, key ->
        this.updateStoreState(currentState, key)
    }
}
