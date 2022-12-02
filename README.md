# Store 5, a Kotlin Multiplatform Library for Building Network-Resilient Applications

## Problems

- Modern software needs data representations to be fluid and always available.
- Users expect their UI experience to never be compromised (blocked) by new data loads. Whether an
  application is social, news or business-to-business, users expect a seamless experience both
  online and offline.
- International users expect minimal data downloads as many megabytes of downloaded data can quickly
  result in astronomical phone bills.

## Concepts

1. `Market` is a composition of stores and systems.
2. `Store` interacts with a local source of `Item(s)` through a `Persister`.
3. `Bookkeeper` tracks local changes and reports failures to sync with the network.
4. `ItemValidator` reports whether a `Store` item is valid.
5. An `App` generally has one `Market` following a singleton pattern for each type of `Item`.
   A `Market` generally has two `Store(s)`: one bound to a memory cache then one bound to a
   database. However, an `App` can have _N_ `Market(s)`. And a `Market` can have _N_ `Store(s)` and
   execute operations in any order.

## Usage

```kotlin
STORE_VERSION = "5.0.0-SNAPSHOT"
```

### Android

```groovy
implementation "org.mobilenativefoundation.store:store5:$STORE_VERSION"
```

### Multiplatform (Common, JVM, Native)

```kotlin
commonMain {
    dependencies {
        implementation("org.mobilenativefoundation.store:store5:$STORE_VERSION")
    }
}
```

## Provided

### Thread-Safe Memory LRU Cache

```kotlin
class MemoryLruCache(private val maxSize: Int) : Persister<String> {
    internal var cache: Map<String, Node<*>>
    override fun <Output : Any> read(key: String): Flow<Output?>
    override suspend fun <Input : Any> write(key: String, input: Input): Boolean
    override suspend fun delete(key: String): Boolean
    override suspend fun deleteAll(): Boolean
}
```

## Implementation

### 1. Model Market Items

```kotlin
interface Note {
    val id: Int
    val title: String
    val content: String
}
```

### 2. Define and Implement API

```kotlin
interface NoteApi {
    suspend fun getNote(id: Int): Result<Note, NoteException>
    suspend fun postNote(note: Note): Boolean
    suspend fun putNote(id: Int, note: Note): Boolean
}
```

```kotlin
class RealNoteApi(private val client: HttpClient) : Api {
    override suspend fun getNote(id: Int): Result<Note, NoteException> = try {
        client.get("$ROOT_API_URL/notes/$id")
    } catch (throwable: Throwable) {
        NoteException(throwable)
    }
    override suspend fun postNote(note: Note): Boolean = try {
        val response = client.post("$ROOT_API_URL/notes") {
            setBody(note)
            contentType(ContentType.Application.Json)
        }
        response.status == HttpStatusCode.Ok
    } catch (_: Throwable) {
        false
    }
    override suspend fun putNote(id: Int, note: Note): Boolean = try {
        val response = client.post("$ROOT_API_URL/notes/$id") {
            setBody(note)
            contentType(ContentType.Application.Json)
        }
        response.status == HttpStatusCode.Ok
    } catch (_: Throwable) {
        false
    }
}
```

### 3. Provide Memory LRU Cache Store [^1]

```kotlin
private val memoryLruCache = MemoryLruCache(maxSize = 100)
```

```kotlin
fun provideMemoryLruCacheStore(): Store<NoteKey, NoteInput, NoteOutput> = Store.by(
    reader = { key -> memoryLruCache.read(key.encode()) },
    writer = { key, input -> memoryLruCache.write(key.encode(), input) },
    deleter = { key -> memoryLruCache.delete(key.encode()) },
    clearer = { memoryLruCache.deleteAll() }
)
```

### 4. Provide [SQLDelight](https://cashapp.github.io/sqldelight/multiplatform_sqlite/) Store [^1]

##### Install SQL Delight

###### Root-Level Gradle

```kotlin
buildscript {
    dependencies {
        classpath("com.squareup.sqldelight:gradle-plugin:$SQLDELIGHT_VERSION")
    }
}
```

###### Project-Level Gradle

```kotlin
plugins {
    id("com.squareup.slqdelight")
}
kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("com.squareup.sqldelight:runtime:$SQLDELIGHT_VERSION")
                implementation("com.squareup.sqldelight:coroutines-extensions:$SQLDELIGHT_VERSION")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("com.squareup.sqldelight:android-driver:$SQLDELIGHT_VERSION")
            }
        }
        val iosMain by getting {
            dependencies {
                implementation("com.squareup.sqldelight:native-driver:$SQLDELIGHT_VERSION")
            }
        }
    }
}
```

##### Create SQLDelight Database

###### Create Note Table

```roomsql
CREATE TABLE note (
    id TEXT NOT NULL PRIMARY KEY,
    key TEXT UNIQUE,
    title TEXT,
    content TEXT
);

get:
SELECT *
FROM note
WHERE key = ?;

getAll:
SELECT *
FROM note;

upsert:
INSERT OR REPLACE INTO note VALUES ?;

delete:
DELETE FROM note
WHERE key = ?;

deleteAll:
DELETE FROM note;
```

###### Create FailedWrite Table

```roomsql
CREATE TABLE failedWrite (
    key TEXT NOT NULL PRIMARY KEY,
    datetime INTEGER AS Long
);

get:
SELECT *
FROM failedWrite
WHERE key = ?;

upsert:
INSERT OR REPLACE INTO failedWrite VALUES ?;

delete:
DELETE FROM failedWrite
WHERE key = ?;

deleteAll:
DELETE FROM failedWrite;
```

##### Provide SQLDelight Store

```kotlin
fun Database.tryGetNote(key: String): Flow<Note?> = try {
    noteQueries.get(key).asFlow().mapNotNull { it.executeAsOne.convert() }
} catch (_: Throwable) {
    flow {}
}
fun Database.tryWriteNote(key: String, input: Note): Boolean = try {
    noteQueries.upsert(input.convert(key))
    true
} catch (_: Throwable) {
    false
}
fun Database.tryDeleteNote(key: String): Boolean = try {
    noteQueries.delete(key)
    true
} catch (_: Throwable) {
    false
}
fun Database.tryDeleteAllNotes(key: String): Boolean = try {
    noteQueries.deleteAll()
    true
} catch (_: Throwable) {
    false
}
```

```kotlin
fun provideSqlDelightStore(database: Database): Store<NoteKey, NoteInput, NoteOutput> = Store.by(
    reader = { key -> database.tryGetNote(key.encode()) },
    writer = { key, input -> database.tryWriteNote(key.encode(), input) },
    deleter = { key -> database.tryDeleteNote(key.encode()) },
    clearer = { database.tryDeleteAllNotes() }
)
```

### 5. Provide Bookkeeper

```kotlin
fun provideBookkeeper(database: Database): Bookkeeper<NoteKey> = Bookkeeper.by(
    read = { key -> database.tryGetFailedWrite(key.encode()) },
    write = { key, input -> database.tryWriteFailedWrite(key.encode(), input) },
    delete = { key -> database.tryDeleteFailedWrite(key.encode()) },
    deleteAll = { database.tryDeleteAllFailedWrites() }
)
```

### 6. Implement NetworkFetcher

- [ ] TODO

### 7. Implement NetworkUpdater

- [ ] TODO

### 8. Provide Market

```kotlin
fun provideMarket(
    memoryLruCacheStore: Store<NoteKey, NoteInput, NoteOutput>,
    sqlDelightStore: Store<NoteKey, NoteInput, NoteOutput>,
    bookkeeper: Bookkeeper<NoteKey>,
    fetcher: NetworkFetcher<NoteKey, NoteInput, NoteOutput>,
    updater: NetworkUpdater<NoteKey, NoteInput, NoteOutput>,
): Market<NoteKey> = Market.of(
    stores = listOf(memoryLruCacheStore, sqlDelightStore),
    bookkeeper = bookkeeper,
    fetcher = fetcher,
    updater = updater
)
```

### 9. Read From and Write To Market

```kotlin
class NoteViewModel(
    private val key: NoteKey,
    private val coroutineScope: CoroutineScope,
    private val market: Market<NoteKey>
) {
    private val stateFlow = MutableStateFlow<NoteState>(NoteState(NoteViewState.Initial))
    val state: StateFlow<NoteState> = stateFlow

    init {
        loadState(refresh = true)
    }

    fun loadState(
        refresh: Boolean = false,
        onCompletions: List<OnMarketCompletion<NoteOutput>> = listOf()
    ) {
        val reader = MarketReader.by(
            key = key,
            onCompletions = onCompletions,
            refresh = refresh
        )
        coroutineScope.launch {
            market.read(reader).collect { marketResponse ->
                val viewState = when (marketResponse) {
                    Loading -> marketResponse.toLoadingViewState()
                    is Success -> marketResponse.toSuccessViewState()
                    is Failure -> marketResponse.toFailureViewState()
                    Empty -> marketResponse.toEmptyViewState()
                }

                val state = NoteState(viewState)

                stateFlow.value = state
            }
        }
    }

    fun updateTitle(
        nextTitle: String,
        onCompletions: List<OnMarketCompletion<NoteOutput>> = listOf()
    ) {
        val viewState = state.value.viewState

        if (viewState is NoteViewState.Success) {
            val nextNote = viewState.note.copy(title = title)
            val nextViewState = viewState.copy(note = nextNote)
            val nextState = state.value.copy(viewState = nextViewState)
            stateFlow.value = nextState

            val writer = MarketWriter.by(
                key = key,
                input = nextNote,
                onCompletions = onCompletions
            )

            coroutineScope.launch {
                val ok = market.write(writer)
                if (!ok) {
                    val nextState = state.value.copy(error = WRITE_ERROR)
                    stateFlow.value = nextState
                }
            }
        }
    }
}
```

## License

```text
Copyright (c) 2022 Mobile Native Foundation.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
```

[^1]: `Market` can be backed by any `Store`