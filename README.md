# Store 5

## Why We Made Store

- Modern software needs data representations to be fluid and always available.
- Users expect their UI experience to never be compromised (blocked) by new data loads. Whether an
  application is social, news or business-to-business, users expect a seamless experience both
  online and offline.
- International users expect minimal data downloads as many megabytes of downloaded data can quickly
  result in astronomical phone bills.

Store is a Kotlin library for loading data from remote and local sources.

## Concepts

1. `Store` delegate to local data sources when reading/writing/deleting items.
2. `Market` is a composition of Stores.
3. `Bookkeeper` tracks local changes and reports failures to sync with the network.
4. `ItemValidator` reports whether a `Store` item is valid.
5. An `App` generally has one `Market` following a singleton pattern for each type of `Item`.
   A `Market` generally has two `Store(s)`: one bound to a memory cache then one bound to a
   database. However, an `App` can have _N_ `Market(s)`. And a `Market` can have _N_ `Store(s)` and
   execute operations in any order.

## From 0 to offline hero

### Add Store dependency

```kotlin
STORE_VERSION = "5.0.0-alpha02"
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

## Define your data types and delegates 

### 1. Here, we create a Note Type

```kotlin
class Note {
    val id: Int
    val title: String
    val content: String
}
```

### 2. Next, an API to read/write Notes from a web service

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

### 3. Using Store [^1]

#### We provide a memory lru store for you to use but you can always make your own stores as well
```kotlin
private val memoryLruCache = MemoryLruStore<Note>(maxSize = 100)
```


### 4. Create a database Store for example delegating to [SQLDelight](https://cashapp.github.io/sqldelight/multiplatform_sqlite/) [^1]

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
This table will be used for conflict resolution

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

##### Create Accessors for your Database

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
##### Provide SQLDelight Store
```kotlin
fun provideSqlDelightStore(database: Database): Store<NoteKey, NoteInput, NoteOutput> = Store.by(
    reader = { key -> database.tryGetNote(key.encode()) },
    writer = { key, input -> database.tryWriteNote(key.encode(), input) },
    deleter = { key -> database.tryDeleteNote(key.encode()) },
    clearer = { database.tryDeleteAllNotes() }
)
```

### 5. Provide Bookkeeper
We need to give Store a way to track write failures 
(see here for more info https://developer.android.com/topic/architecture/data-layer/offline-first#conflict-resolution)
We will use the DB table we created above
```kotlin
fun provideBookkeeper(database: Database): Bookkeeper<NoteKey> = Bookkeeper.by(
    read = { key -> database.tryGetFailedWrite(key.encode()) },
    write = { key, input -> database.tryWriteFailedWrite(key.encode(), input) },
    delete = { key -> database.tryDeleteFailedWrite(key.encode()) },
    deleteAll = { database.tryDeleteAllFailedWrites() }
)
```

### 6. Implementing NetworkFetcher

```kotlin
fun provideNetworkFetcher(
    api: Api
): NetworkFetcher<NoteKey, NoteInput, NoteOutput> = NetworkFetcher.by(
    get = { key -> api.getNote(key.id) },
    post = { key, input -> api.putNote(key.id!!) },
    converter = { it }
)
```

### 7. Implement NetworkUpdater

```kotlin
fun provideNetworkUpdater(
    api: Api
): NetworkUpdater<NoteKey, NoteInput, NoteOutput> = NetworkUpdater.by(
    post = { key, input ->
        when (key.id) {
            null -> api.postNote(input)
            else -> api.putNote(key.id, input)
        }
    },
    onCompletion = OnNetworkCompletion(
        onSuccess = {},
        onFailure = {}
    ),
    converter = { it }
)
```

### 8. Bringing it all together in a Market

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

### 9. Using your Market

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
        val reader = ReadRequest.by(
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

            val writer = WriteRequest.by(
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

```textm
Copyright (c) 2022 Mobile Native Foundation.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
```

[^1]: `Market` can be backed by any `Store`
