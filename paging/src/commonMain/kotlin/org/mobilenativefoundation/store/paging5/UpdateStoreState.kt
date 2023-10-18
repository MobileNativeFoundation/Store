@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.store5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.impl.extensions.fresh


typealias LoadedCollection<Id> = StoreState.Loaded.Collection<Id, Identifiable.Single<Id>, Identifiable.Collection<Id, Identifiable.Single<Id>>>


/**
 * Updates the Store's state based on a provided key and a retrieval mechanism.
 * @param currentState The current state of the Store.
 * @param key The key that dictates how the state should be updated.
 * @param get A lambda that defines how to retrieve data from the Store based on a key.
 */
private suspend fun <Id : Any, Key : StoreKey<Id>, Output : Identifiable<Id>> updateStoreState(
    currentState: StoreState<Id, Output>,
    key: Key,
    get: suspend (key: Key) -> Output,
): StoreState<Id, Output> {
    return try {
        if (key !is StoreKey.Collection<*>) throw IllegalArgumentException("Invalid key type")

        val lastOutput = when (currentState) {
            is StoreState.Loaded.Collection<*, *, *> -> {
                val data = (currentState as LoadedCollection<Id>).data
                println("DATA = $data")
                data
            }

            else -> {
                println("NULL")
                null
            }
        }

        val nextOutput = get(key) as Identifiable.Collection<Id, Identifiable.Single<Id>>

        val output = (lastOutput?.insertItems(key.loadType, nextOutput.items) ?: nextOutput)

        println("OUTPUT * = $lastOutput $output")
        StoreState.Loaded.Collection(output) as StoreState<Id, Output>

    } catch (error: Exception) {
        StoreState.Error.Exception(error)
    }
}

/**
 * Updates the [Store]'s state based on a provided key.
 * @see [updateStoreState].
 */
suspend fun <Id : Any, Key : StoreKey<Id>, Output : Identifiable<Id>> Store<Key, Output>.updateStoreState(
    currentState: StoreState<Id, Output>,
    key: Key
): StoreState<Id, Output> {
    return updateStoreState(
        currentState,
        key
    ) {
        this.fresh(it)
    }
}

/**
 * Updates the [MutableStore]'s state based on a provided key.
 * @see [updateStoreState].
 */
@OptIn(ExperimentalStoreApi::class)
suspend fun <Id : Any, Key : StoreKey<Id>, Output : Identifiable<Id>> MutableStore<Key, Output>.updateStoreState(
    currentState: StoreState<Id, Output>,
    key: Key
): StoreState<Id, Output> {
    return updateStoreState(
        currentState,
        key
    ) {
        val output = this.fresh<Key, Output, Any>(it)
        println("KEY = $key")
        println("OUTPUT = $output")
        println("CURRENT STATE = $currentState")
        output
    }
}

