@file:Suppress("UNCHECKED_CAST")

package org.mobilenativefoundation.store.paging5

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.impl.extensions.get


/**
 * Interface defining items that can be identified.
 * The identifiable items can either be standalone entities or collections of entities.
 *
 * This structure is particularly useful in scenarios where data can be represented
 * both as individual units or as groups (collections) of units. For example, in a data fetch
 * scenario, an API could return a single item or a list of items.
 */
sealed interface Identifiable<out Id : Any> {

    /**
     * Represents a single identifiable item.
     * Each single item must have a unique identifier, represented by the 'id' property.
     */
    interface Single<Id : Any> : Identifiable<Id> {
        val id: Id
    }

    /**
     * Represents a collection of identifiable items.
     * The collection is essentially a list of single items.
     */
    interface Collection<Id : Any> : Identifiable<Id> {
        val items: List<Single<Id>>
    }
}


/**
 * Interface defining keys used by the Store for data fetch operations.
 *
 * The StoreKey allows the Store to fetch either individual items or collections of items.
 * Depending on the use-case, the fetch can be a simple ID-based fetch, a page-based fetch,
 * or a cursor-based fetch. Sorting and filtering options are also provided.
 */
sealed interface StoreKey<out Id : Any> {

    /**
     * Represents a key for fetching a single item based on its ID.
     */
    interface Single<Id : Any> : StoreKey<Id> {
        val id: Id
    }

    /**
     * Represents a key for fetching collections (lists) of items.
     */
    sealed interface Collection<Id : Any> : StoreKey<Id> {

        /**
         * Represents a key for page-based fetching.
         * This includes the page number, size of the page, sorting option, and filters.
         */
        interface Page : Collection<Nothing> {
            val page: Int
            val size: Int
            val sort: Sort?
            val filters: List<Filter<*>>?
        }

        /**
         * Represents a key for cursor-based fetching.
         * This includes a cursor string, size of the fetch, sorting option, and filters.
         */
        interface Cursor<Id : Any> : Collection<Id> {
            val cursor: Id
            val size: Int
            val sort: Sort?
            val filters: List<Filter<*>>?
        }
    }

    /**
     * Enum defining sorting options that can be applied during fetching.
     */
    enum class Sort {
        NEWEST,
        OLDEST,
        ALPHABETICAL,
        REVERSE_ALPHABETICAL
    }

    /**
     * Class defining filters that can be applied during fetching.
     * Each filter consists of a list of items and a block that defines the filtering criteria.
     */
    interface Filter<Value : Any> {
        operator fun invoke(items: List<Value>): List<Value>
    }
}


/**
 * Interface defining different states of data fetch operations.
 */
sealed interface StoreState<out Id : Any, out Output : Identifiable<Id>> {
    data object Initial : StoreState<Nothing, Nothing>
    data object Loading : StoreState<Nothing, Nothing>

    sealed interface Loaded<Id : Any, Output : Identifiable<Id>> : StoreState<Id, Output> {
        data class Single<Id : Any, Output : Identifiable.Single<Id>>(val data: Output) : Loaded<Id, Output>

        data class Collection<Id : Any, Output : Identifiable.Collection<Id>>(val data: Output) : Loaded<Id, Output>
    }

    sealed interface Error : StoreState<Nothing, Nothing> {
        data class Exception<CustomException : Any>(val error: CustomException) : Error
        data class Message(val error: String) : Error
    }
}


/**
 * Extension function on the Store class to provide a stateful composable.
 * It manages the state of a fetch operation for a given key.
 *
 * @param key The key based on which data will be fetched.
 * @return A composable function that returns the current state of the fetch operation.
 */
fun <Id : Any, Key : StoreKey<Id>, Output : Identifiable<Id>> Store<Key, Output>.stateful(key: Key): @Composable () -> StoreState<Id, Output> {

    @Composable
    fun launch(): StoreState<Id, Output> {
        // Remember and manage the fetch operation's state.
        var state by rememberSaveable { mutableStateOf<StoreState<Id, Output>>(StoreState.Loading) }

        LaunchedEffect(key) {
            state = try {
                key as StoreKey.Collection<Id>
                val output = this@stateful.get(key) as Identifiable.Collection<Id>
                StoreState.Loaded.Collection(output) as StoreState<Id, Output>

            } catch (error: Exception) {
                // Handle and store exceptions in the state.
                StoreState.Error.Exception(error)
            }
        }

        return state
    }

    return ::launch
}


/**
 * A custom LazyColumn that supports prefetching.
 * Detects when the user is close to the end of the displayed items and triggers a fetch for subsequent data.
 */
@Composable
fun <Id : Any, T : Identifiable.Single<Id>> PrefetchingLazyColumn(
    items: List<T>,
    threshold: Int = 3,
    onPrefetch: (nextCursor: Id) -> Unit,
    content: @Composable LazyItemScope.(T) -> Unit
) {
    LazyColumn {
        itemsIndexed(items) { index, item ->
            if (index >= items.size - threshold) {
                onPrefetch(items.last().id)
            }
            content(item)
        }
    }
}


/**
 * A custom LazyColumn that supports prefetching.
 * Detects when the user is close to the end of the displayed items and triggers a fetch for subsequent data.
 */
@Composable
fun <Id : Any, Value : Identifiable.Single<Id>> PrefetchingLazyColumn(
    items: List<Value>,
    threshold: Int = 3,
    onPrefetch: () -> Unit,
    content: @Composable LazyItemScope.(Value) -> Unit
) {
    LazyColumn {
        itemsIndexed(items) { index, item ->
            if (index >= items.size - threshold) {
                onPrefetch()
            }
            content(item)
        }
    }
}


/**
 * Extension function on the Store class to launch a paging store.
 * For each key in the provided StateFlow<Key>, it maps to a flow that emits the corresponding store state.
 *
 * @param keys A StateFlow containing keys based on which data will be fetched.
 * @param scope The coroutine scope in which the operations will be launched.
 * @return A StateFlow that emits the store state corresponding to each key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <Id : Any, Key : StoreKey<Id>, Output : Identifiable<Id>> Store<Key, Output>.launchPagingStore(
    keys: StateFlow<Key>,
    scope: CoroutineScope
): StateFlow<StoreState<Id, Output>> {
    // For each key, create a flow that computes and emits the store state.
    return keys.flatMapConcat { key ->
        flow {
            try {
                // Launch a molecule to reactively compute the store state for the given key.
                val storeState = scope.launchMolecule(mode = RecompositionMode.ContextClock) {
                    stateful(key)
                }
                // Emit the computed store state to the resulting flow.
                emit(storeState.value.invoke())
            } catch (error: Exception) {
                // Handle potential errors during state computation.
                emit(StoreState.Error.Exception(error))
            }
        }
    }.stateIn(
        scope,
        SharingStarted.Lazily,
        StoreState.Initial
    ) // Convert the flow to a StateFlow with an initial state.
}

/**
 * A composable function designed for cursor-based pagination in the Store.
 * It manages and displays data based on different states: initial, loading, loaded, and error.
 *
 * @param key The initial key for fetching data.
 * @param initialContent Composable to display during the initial state.
 * @param loadingContent Composable to display during the loading state.
 * @param errorContent Composable to display when an error occurs.
 * @param onPrefetch Function to determine the next key based on the current cursor.
 * @param content Composable to display the fetched items.
 */
@Composable
inline fun <reified Id : Any, reified Value : Identifiable.Single<Id>> Store<StoreKey<Id>, Identifiable<Id>>.cursor(
    key: StoreKey<Id>,
    crossinline initialContent: @Composable () -> Unit = {},
    crossinline loadingContent: @Composable () -> Unit = {},
    crossinline errorContent: @Composable (error: StoreState.Error) -> Unit = {},
    crossinline onPrefetch: (nextCursor: Id) -> StoreKey<Id>,
    crossinline content: @Composable (Value) -> Unit
) {
    // MutableStateFlow to hold the current key for data fetch.
    val keys = MutableStateFlow(key)
    // Remembered coroutine scope for launching coroutines in Compose.
    val scope = rememberCoroutineScope()
    // Current state of the store fetched using the launchPagingStore function.
    val state = launchPagingStore(keys, scope).collectAsState()

    // Render UI based on the current state.
    when (val storeState = state.value) {
        is StoreState.Error -> errorContent(storeState)
        StoreState.Initial -> initialContent()
        is StoreState.Loaded.Collection<*, *> -> {
            val items = storeState.data.items as List<Identifiable.Single<Id>>

            PrefetchingLazyColumn(
                items = items,
                onPrefetch = { nextCursor ->
                    val nextKey = onPrefetch(nextCursor)
                    keys.value = nextKey
                }
            ) { item ->
                if (item is Value) {
                    content(item)
                } else {
                    errorContent(StoreState.Error.Message("Unexpected item type: ${item::class.simpleName}"))
                }
            }
        }

        is StoreState.Loaded.Single<*, *> -> errorContent(StoreState.Error.Message("Single item pagination not supported."))
        StoreState.Loading -> loadingContent()
    }
}
