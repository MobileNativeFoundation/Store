# Technical Design Doc: Native Paging Support in Store5

## Context and Scope
Feature request: [MobileNativeFoundation/Store#250](https://github.com/MobileNativeFoundation/Store/issues/250)

This proposal addresses the need for paging support in Store. This enhancement aims to provide a simple, efficient, and flexible way to handle complex operations on large datasets.

## Goals and Non-Goals
### Goals
- Provide native support for page-based and cursor-based fetches, handling both single items and collections.
- Enable read and write operations within a paging store.
- Support complex loading and fetching operations such as sorting and filtering.
- Ensure thread safety and concurrency support.
- Layer on top of existing Store APIs: no breaking changes!
### Non-Goals
- Integration with Paging3.
- Providing a one-size-fits-all solution: our approach should be flexible to cater to different use cases.

## The Actual Design

### APIs
#### StoreKey
An interface that defines keys used by Store for data-fetching operations. Allows Store to load individual items and collections of items. Provides mechanisms for ID-based fetch, page-based fetch, and cursor-based fetch. Includes options for sorting and filtering.

```kotlin
    interface StoreKey<out Id : Any> {
        interface Single<Id : Any> : StoreKey<Id> {
            val id: Id
        }
        interface Collection<out Id : Any> : StoreKey<Id> {
            val insertionStrategy: InsertionStrategy
            interface Page : Collection<Nothing> {
                val page: Int
                val size: Int
                val sort: Sort?
                val filters: List<Filter<*>>?
            }
            interface Cursor<out Id : Any> : Collection<Id> {
                val cursor: Id?
                val size: Int
                val sort: Sort?
                val filters: List<Filter<*>>?
            }
        }
    }
```

#### StoreData
An interface that defines items that can be uniquely identified. Every item that implements the `StoreData` interface must have a means of identification. This is useful in scenarios when data can be represented as singles or collections.

```kotlin
    interface StoreData<out Id : Any> {
        interface Single<Id : Any> : StoreData<Id> {
            val id: Id
        }
        interface Collection<Id : Any, S : Single<Id>> : StoreData<Id> {
            val items: List<S>
            fun copyWith(items: List<S>): Collection<Id, S>
            fun insertItems(strategy: InsertionStrategy, items: List<S>): Collection<Id, S>
        }
    }
```

#### KeyProvider
An interface to derive keys based on provided data. `StoreMultiCache` depends on `KeyProvider` to:

1. Derive a single key for a collection item based on the collection’s key and that item’s value.
2. Insert a single item into the correct collection based on its key and value.

```kotlin
    interface KeyProvider<Id : Any, Single : StoreData.Single<Id>> {
        fun from(key: StoreKey.Collection<Id>, value: Single): StoreKey.Single<Id>
        fun from(key: StoreKey.Single<Id>, value: Single): StoreKey.Collection<Id>
    }
```

### Implementations

#### StoreMultiCache
Thread-safe caching system with collection decomposition. Manages data with utility functions to get, invalidate, and add items to the cache. Depends on `StoreMultiCacheAccessor` for internal data management. Should be used instead of `MultiCache`.

```kotlin
    class StoreMultiCache<Id : Any, Key : StoreKey<Id>, Single : StoreData.Single<Id>, Collection : StoreData.Collection<Id, Single>, Output : StoreData<Id>>(
        private val keyProvider: KeyProvider<Id, Single>,
        singlesCache: Cache<StoreKey.Single<Id>, Single> = CacheBuilder<StoreKey.Single<Id>, Single>().build(),
        collectionsCache: Cache<StoreKey.Collection<Id>, Collection> = CacheBuilder<StoreKey.Collection<Id>, Collection>().build(),
    ): Cache<Key, Output>
```

#### StoreMultiCacheAccessor
Thread-safe intermediate data manager for a caching system supporting list decomposition. Tracks keys for rapid data retrieval and modification.

#### LaunchPagingStore
Main entry point for the paging mechanism. This will launch and manage a `StateFlow` that reflects the current state of the Store.

```kotlin
    fun <Id : Any, Key : StoreKey<Id>, Output : StoreData<Id>> Store<Key, Output>.launchPagingStore(
        scope: CoroutineScope,
        keys: Flow<Key>,
    ): StateFlow<StoreReadResponse<Output>>
    
    @OptIn(ExperimentalStoreApi::class)
    fun <Id : Any, Key : StoreKey<Id>, Output : StoreData<Id>> MutableStore<Key, Output>.launchPagingStore(
        scope: CoroutineScope,
        keys: Flow<Key>,
    ): StateFlow<StoreReadResponse<Output>>
```

## Usage
### StoreKey Example
```kotlin
    sealed class ExampleKey : StoreKey<String> {
        data class Cursor(
            override val cursor: String?,
            override val size: Int,
            override val sort: StoreKey.Sort? = null,
            override val filters: List<StoreKey.Filter<*>>? = null,
            override val insertionStrategy: InsertionStrategy = InsertionStrategy.APPEND
        ) : StoreKey.Collection.Cursor<String>, ExampleKey()
    
        data class Single(
            override val id: String
        ) : StoreKey.Single<String>, ExampleKey()
    }
```

### StoreData Example
```kotlin
    sealed class ExampleData : StoreData<String> {
        data class Single(val postId: String, val title: String) : StoreData.Single<String>, ExampleData() {
            override val id: String get() = postId
        }
    
        data class Collection(val singles: List<Single>) : StoreData.Collection<String, Single>, ExampleData() {
            override val items: List<Single> get() = singles
            override fun copyWith(items: List<Single>): StoreData.Collection<String, Single> = copy(singles = items)
            override fun insertItems(strategy: InsertionStrategy, items: List<Single>): StoreData.Collection<String, Single> {
    
                return when (strategy) {
                    InsertionStrategy.APPEND -> {
                        val updatedItems = items.toMutableList()
                        updatedItems.addAll(singles)
                        copyWith(items = updatedItems)
                    }
    
                    InsertionStrategy.PREPEND -> {
                        val updatedItems = singles.toMutableList()
                        updatedItems.addAll(items)
                        copyWith(items = updatedItems)
                    }
                }
            }
        }
    }
```

### LaunchPagingStore Example
```kotlin
    @OptIn(ExperimentalStoreApi::class)
    class ExampleViewModel(
        private val store: MutableStore<ExampleKey, ExampleData>,
        private val coroutineScope: CoroutineScope = viewModelScope,
        private val loadSize: Int = DEFAULT_LOAD_SIZE
    ) : ViewModel() {
    
        private val keys = MutableStateFlow(ExampleKey.Cursor(null, loadSize))
        private val _loading = MutableStateFlow(false)
        private val _error = MutableStateFlow<Throwable?>(null)
    
        val stateFlow = store.launchPagingStore(coroutineScope, keys)
        val loading: StateFlow<Boolean> = _loading.asStateFlow()
        val error: StateFlow<Throwable?> = _error.asStateFlow()
    
        init {
            TODO("Observe loading and error states and perform any other necessary initializations")
        }
    
        fun loadMore() {
            if (_loading.value) return // Prevent loading more if already loading
            _loading.value = true
    
            coroutineScope.launch {
                try {
                    val currentKey = keys.value
                    val currentCursor = currentKey.cursor
                    val nextCursor = determineNextCursor(currentCursor)
                    val nextKey = currentKey.copy(cursor = nextCursor)
                    keys.value = nextKey
                } catch (e: Throwable) {
                    _error.value = e
                } finally {
                    _loading.value = false
                }
            }
        }
    
        fun write(key: ExampleKey.Single, value: ExampleData.Single) {
            coroutineScope.launch {
                try {
                    store.write(StoreWriteRequest.of(key, value))
                } catch (e: Throwable) {
                    _error.value = e
                }
            }
        }
    
        private fun determineNextCursor(cursor: String?): String? {
            // Implementation based on specific use case
            // Return the next cursor or null if there are no more items to load
            TODO("Provide an implementation or handle accordingly")
        }
    
        companion object {
            private const val DEFAULT_LOAD_SIZE = 100
        }
    }
```

## Degree of Constraint
- Data items must implement the `StoreData` interface, ensuring they can be uniquely identified.
- Keys for loading data must implement the `StoreKey` interface.

## Deprecations
- MultiCache
- Identifiable

## Alternatives Considered
### Tailored Solution for Paging
#### Direct integration with Paging3
Paging3 doesn’t have built-in support for:
- Singles and collections
- Write operations
- Sorting and filtering operations

### Custom `StoreKey` and `StoreData` Structures
#### Loose Typing
#### Annotations and Reflection
#### Functional Programming Approach

## Cross-Cutting Concerns
- Will Paging3 extensions be a maintenance nightmare?
- Will these APIs be simpler than Paging3?

## Future Directions
- Bindings for Paging3 (follow-up PR)
- Support for KMP Compose UI (follow-up PR)