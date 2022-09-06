# Store 5

Store is a Kotlin Multiplatform library for building network-resilient applications.

## Concepts

A `Market` is a composition of stores and systems. A `Store` interacts with one data source, or a `Storage`.
A `ConflictResolution` settles discrepancies among remote and local storages.

A market always has one conflict resolution strategy. However, the Store library is flexible and unopinionated on implementation.
An application can have N markets. And a market can have N stores and execute operations in any order.

Typical applications have one market following a singleton pattern. Most of the time a market has two stores: a memory
store bound to a memory cache then a disk store bound to a database.

### Storage

```kotlin
interface Storage<Key : Any> {
    fun <Output : Any> read(key: Key): Flow<Output?>
    fun <Input : Any> write(key: Key, input: Input): Boolean
    fun delete(key: Key): Boolean
    fun clear(): Boolean
}
```

### Store

```kotlin
class Store<Key, Input, Output> private constructor(
    val read: Read<Key, Output>,
    val write: Write<Key, Input>,
    val delete: Delete<Key>,
    val clear: Clear
)
```

### Market

```kotlin
interface Market<Key : Any> {
    suspend fun <Input : Any, Output : Any> read(
        request: Request.Read<Key, Input, Output>
    ): MutableSharedFlow<Response<Output>>
    suspend fun <Input : Any, Output : Any> write(
        request: Request.Write<Key, Input, Output>
    ): Boolean
    suspend fun delete(key: Key): Boolean
    suspend fun clear(): Boolean
}
```

#### Market.Request

```kotlin
sealed class Request<Key> {
    abstract val key: Key

    class Write<Key : Any, Input : Any, Output : Any> constructor(
        override val key: Key,
        val input: Input,
        val request: Fetch.Request.Post<Key, Input, Output>,
        val serializer: KSerializer<Input>,
        val onCompletions: List<OnCompletion<Output>>
    ) : Request<Key>()

    class Read<Key : Any, Input : Any, Output : Any> constructor(
        override val key: Key,
        val request: Fetch.Request.Get<Key, Input, Output>,
        val serializer: KSerializer<Output>,
        val onCompletions: List<OnCompletion<Output>>,
        val refresh: Boolean = false
    ) : Request<Key>()
}
```

#### Market.Response

```kotlin
sealed class Response<out Output> {
    object Loading : Response<Nothing>()
    data class Success<Output>(val value: Output, val origin: Origin) : Response<Output>()
    data class Failure(val error: Throwable, val origin: Origin) : Response<Nothing>()
    object Empty : Response<Nothing>()
}
```

## Usage

```kotlin
commonMain {
    dependencies {
        implementation("com.dropbox.mobile.store:store5:5.0.0-alpha01")
    }
}
```

## Implementation

### Market provides a memory LRU cache

```kotlin
class ShareableLruCache(private val maxSize: Int) : Storage<String> {
    internal var cache = shareableMutableMapOf<String, Node<*>>()
    override fun <Output : Any> read(key: String): Flow<Output?>
    override fun <Input : Any> write(key: String, input: Input): Boolean
    override fun delete(key: String): Boolean
    override fun clear(): Boolean
}
```

### Create stores with `Store.Builder`

```kotlin
@Provides
@Named("MemoryLruCacheStore")
@Singleton
fun provideMemoryLruCacheStore(): Store<Key, Notebook, Notebook> {
    val memoryLruCache = ShareableLruCache(10)
    return Store.Builder<Key, Notebook, Notebook>()
        .read { key -> memoryLruCache.read(serializer.encodeToString(key)) }
        .write { key, input -> memoryLruCache.write(serializer.encodeToString(key), input) }
        .delete { key -> memoryLruCache.delete(serializer.encodeToString(key)) }
        .clear { memoryLruCache.clear() }
        .build()
}

@Provides
@Named("DatabaseStore")
@Singleton
fun provideDatabaseStore(database: NotesDatabase): Store<Key, Notebook, Notebook> {
    return Store.Builder<Key, Notebook, Notebook>()
        .read { key ->
            flow {
                database.noteQueries
                    .read(key.key)
                    .executeAsList()
                    .let { notes -> emit(Notebook.Note(notes.last().asMarketNote())) }
            }
        }
        .write { key, input -> database.tryWrite(key, input) }
        .delete { key -> database.tryDelete(serializer.encodeToString(key)) }
        .clear { database.tryClear() }
        .build()
}
```

### Create write requests with `Market.Request.Write.Builder`

```kotlin
class NotesRepository {
    private fun buildWriter(key: Key, notebook: Notebook) =
        Market.Request.Write.Builder<Key, Notebook, Notebook>(key, notebook)
            .request(buildPostRequest())
            .onCompletion(Market.Request.Write.OnCompletion.Builder<Notebook>()
                .onSuccess { }
                .onFailure {}
                .build()
            )
            .build<Notebook>()
}
```

### Create read requests with `Market.Request.Read.Builder`

```kotlin
class NotesRepository {
    private fun buildReader(key: Key) =
        Market.Request.Read.Builder<Key, Notebook, Notebook>(key = key, refresh = true)
            .request(buildGetRequest())
            .build<Notebook>()
}
```

### Create a market with `Market.of()`

```kotlin
@Provides
@Singleton
fun provideMarket(
    @Named("MarketScope") coroutineScope: CoroutineScope,
    @Named("MemoryLruCacheStore") memoryLruCacheStore: Store<Key, Notebook, Notebook>,
    @Named("DatabaseStore") databaseStore: Store<Key, Notebook, Notebook>,
    conflictResolution: ConflictResolution<Key, Notebook, Notebook>
): Market<Key> = Market.of(
        coroutineScope = coroutineScope,
        stores = listOf(memoryLruCacheStore, databaseStore),
        conflictResolution = conflictResolution
    )
```

## Examples

- [Notes](https://github.com/MobileNativeFoundation/Store/samples/notes)

## License

```text
Copyright (c) 2022 Dropbox, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
