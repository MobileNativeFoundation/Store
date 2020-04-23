# Store 4

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.dropbox.mobile.store/store4/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.dropbox.mobile.store/store4/)

[![codecov](https://codecov.io/gh/dropbox/Store/branch/master/graph/badge.svg)](https://codecov.io/gh/dropbox/Store)

Store is a Kotlin library for loading data from remote and local sources.

### The Problems:

+ Modern software needs data representations to be fluid and always available.
+ Users expect their UI experience to never be compromised (blocked) by new data loads. Whether an application is social, news or business-to-business, users expect a seamless experience both online and offline.
+ International users expect minimal data downloads as many megabytes of downloaded data can quickly result in astronomical phone bills.

A Store is a class that simplifies fetching, sharing, storage, and retrieval of data in your application. A Store is similar to the [Repository pattern](https://msdn.microsoft.com/en-us/library/ff649690.aspx) while exposing an API built with [Coroutines](https://kotlinlang.org/docs/reference/coroutines-overview.html) that adheres to a unidirectional data flow.

Store provides a level of abstraction between UI elements and data operations.

### Overview

A Store is responsible for managing a particular data request. When you create an implementation of a Store, you provide it with a `Fetcher`, a function that defines how data will be fetched over network. You can also define how your Store will cache data in-memory and on-disk. Since Store returns your data as a `Flow`, threading is a breeze! Once a Store is built, it handles the logic around data flow, allowing your views to use the best data source and ensuring that the newest data is always available for later offline use.

Store leverages multiple request throttling to prevent excessive calls to the network and disk cache. By utilizing Store, you eliminate the possibility of flooding your network with the same request while adding two layers of caching (memory and disk) as well as ability to add disk as a source of truth where you can modify the disk directly without going through Store (works best with databases that can provide observables sources like [Jetpack Room](https://developer.android.com/jetpack/androidx/releases/room), [SQLDelight](https://github.com/cashapp/sqldelight) or [Realm](https://realm.io/products/realm-database/))

### How to include in your project

Artifacts are hosted on **Maven Central**.

###### Latest version:

```groovy
def store_version = "4.0.0-alpha05"
```

###### Add the dependency to your `build.gradle`:

```groovy
implementation "com.dropbox.mobile.store:store4:${store_version}"
```

###### Set the source & target compatibilities to `1.8`

```groovy
android {
    compileOptions {
        sourceCompatibility 1.8
        targetCompatibility 1.8
    }
    ...
}
```

### Fully Configured Store
Let's start by looking at what a fully configured Store looks like. We will then walk through simpler examples showing each piece:

```kotlin
StoreBuilder
    .from(
        fetcher = nonFlowValueFetcher { api.fetchSubreddit(it, "10").data.children.map(::toPosts) },
        sourceOfTruth = SourceOfTrue.from(
            reader = db.postDao()::loadPosts,
            writer = db.postDao()::insertPosts,
            delete = db.postDao()::clearFeed,
            deleteAll = db.postDao()::clearAllFeeds
        )
    ).build()
```

With the above setup you have:
+ In-memory caching for rotation
+ Disk caching for when users are offline
+ Throttling of API calls when parallel requests are made for the same resource
+ Rich API to ask for data whether you want cached, new or a stream of future data updates.

And now for the details:

### Creating a Store

You create a Store using a builder. The only requirement is to include a `Fetcher` which is just a `typealias` to a  function that returns a `Flow<FetcherResult<ReturnType>>`.


```kotlin
val store = StoreBuilder
        .from(valueFetcher { articleId -> api.getArticle(articleId) }) // api returns Flow<Article>
        .build()
```

Store uses generic keys as identifiers for data. A key can be any value object that properly implements `toString()`, `equals()` and `hashCode()`. When your `Fetcher` function is called, it will be passed a particular `Key` value. Similarly, the key will be used as a primary identifier within caches (Make sure to have a proper `hashCode()`!!).

Note: We highly recommend using built-in types that implement `equals` and `hashcode` or Kotlin `data` classes for complex keys.

### Public Interface - Stream

The primary function provided by a `Store` instance is the `stream` function which has the following signature:

```kotlin
fun stream(request: StoreRequest<Key>): Flow<StoreResponse<Output>>
```
Each `stream` call receives a `StoreRequest` object, which defines which key to fetch and which data sources to utilize.
The response is a `Flow` of `StoreResponse`. `StoreResponse` is a Kotlin sealed class that can be either
a `Loading`, `Data` or `Error` instance.
Each `StoreResponse` includes an `origin` field which specifies where the event is coming from.

* The `Loading` class only has an `origin` field. This can provide you information like "network is fetching data", which can be a good signal to activate the loading spinner in your UI.
* The `Data` class has a `value` field which includes an instance of the type returned by `Store`.
* The `Error` class includes an `error` field that contains the exception thrown by the given `origin`.

When an error happens, `Store` does not throw an exception, instead, it wraps it in a `StoreResponse.Error` type which allows `Flow` to continue so that it can still receive updates that might be triggered by either changes in your data source or subsequent fetch operations.

```kotlin
lifecycleScope.launchWhenStarted {
  store.stream(StoreRequest.cached(key = key, refresh=true)).collect { response ->
    when(response) {
        is StoreResponse.Loading -> showLoadingSpinner()
        is StoreResponse.Data -> {
            if (response.origin == ResponseOrigin.Fetcher) hideLoadingSpinner()
            updateUI(response.value)
        }
        is StoreResponse.Error -> {
            if (response.origin == ResponseOrigin.Fetcher) hideLoadingSpinner()
            showError(response.error)
        }
    }
  }
}
```

For convenience, there are `Store.get(key)` and `Store.fresh(key)` extension functions.

* `suspend fun Store.get(key: Key): Value`: This method returns a single value for the given key. If available, it will be returned from the in memory cache or the sourceOfTruth. An error will be thrown if no value is available in either the `cache` or `sourceOfTruth`, and the `fetcher` fails to load the data from the network.
* `suspend fun Store.fresh(key: Key): Value`: This method returns a single value for the given key that is obtained by querying the fetcher. An error will be thrown if the `fetcher` fails to load the data from the network, regardless of whether any value is available in the `cache` or `sourceOfTruth`.

```kotlin
lifecycleScope.launchWhenStarted {
  val article = store.get(key)
  updateUI(article)
}
```

The first time you call to `suspend store.get(key)`, the response will be stored in an in-memory cache and in the sourceOfTruth, if provided.
All subsequent calls to `store.get(key)` with the same `Key` will retrieve the cached version of the data, minimizing unnecessary data calls. This prevents your app from fetching fresh data over the network (or from another external data source) in situations when doing so would unnecessarily waste bandwidth and battery. A great use case is any time your views are recreated after a rotation, they will be able to request the cached data from your Store. Having this data available can help you avoid the need to retain this in the view layer.

By default, 100 items will be cached in memory for 24 hours. You may [pass in your own memory policy to override the default policy](#Configuring-In-memory-Cache).


### Busting through the cache

Alternatively, you can call `store.fresh(key)` to get a `suspended result` that skips the memory (and optional disk cache).


A good use case is overnight background updates use `fresh()` to make sure that calls to `store.get()` will not have to hit the network during normal usage. Another good use case for `fresh()` is when a user wants to pull to refresh.

Calls to both `fresh()` and `get()` emit one value or throw an error.


### Stream
For real-time updates, you may also call `store.stream()` which returns a `Flow<T>` that emits each time a new item is returned from your store. You can think of stream as a way to create reactive streams that update when you db or memory cache updates

example calls:
```kotlin
lifecycleScope.launchWhenStarted {
    store.stream(StoreRequest.cached(3, refresh = false)) //will get cached value followed by any fresh values, refresh will also trigger network call if set to `true` even if the data is available in cache or disk.
        .collect {}
    store.stream(StoreRequest.fresh(3)) //skip cache, go directly to fetcher
        .collect {}
}
```

### Inflight Debouncer

To prevent duplicate requests for the same data, Store offers an inflight debouncer. If the same request is made as a previous identical request that has not completed, the same response will be returned. This is useful for situations when your app needs to make many async calls for the same data at startup or when users are obsessively pulling to refresh. As an example, The New York Times news app asynchronously calls `ConfigStore.get()` from 12 different places on startup. The first call blocks while all others wait for the data to arrive. We have seen a dramatic decrease in the app's data usage after implementing this inflight logic.

### Disk as Cache

Stores can enable disk caching by passing a `SourceOfTruth` into the builder. Whenever a new network request is made, the Store will first write to the disk cache and then read from the disk cache.

### Disk as Single Source of Truth
Providing `sourceOfTruth` whose `reader` function can return a `Flow<Value?>` allows you to make Store treat your disk as source of truth.
Any changes made on disk, even if it is not made by Store, will update the active `Store` streams.

This feature, combined with persistence libraries that provide observable queries ([Jetpack Room](https://developer.android.com/jetpack/androidx/releases/room), [SQLDelight](https://github.com/cashapp/sqldelight) or [Realm](https://realm.io/products/realm-database/))
allows you to create offline first applications that can be used without an active network connection while still providing a great user experience.



```kotlin
StoreBuilder
    .from(
        fetcher = nonFlowValueFetcher { api.fetchSubreddit(it, "10").data.children.map(::toPosts) },
        sourceOfTruth = SourceOfTrue.from(
            reader = db.postDao()::loadPosts,
            writer = db.postDao()::insertPosts,
            delete = db.postDao()::clearFeed,
            deleteAll = db.postDao()::clearAllFeeds
        )
    ).build()
```

Stores don’t care how you’re storing or retrieving your data from disk. As a result, you can use Stores with object storage or any database (Realm, SQLite, CouchDB, Firebase etc). Technically, there is nothing stopping you from implementing an in-memory cache for the "sourceOfTruth" implementation and instead have two levels of in-memory caching--one with inflated and one with deflated models, allowing for sharing of the “sourceOfTruth” cache data between stores.

If using SQLite we recommend working with [Room](https://developer.android.com/topic/libraries/architecture/room) which returns a `Flow` from a query

The above builder is how we recommend working with data on Android. With the above setup you have:
+ Memory caching with TTL & Size policies
+ Disk caching with simple integration with Room
+ In-flight request management
+ Ability to get cached data or bust through your caches (`get()` vs. `fresh()`)
+ Ability to listen for any new emissions from network (stream)
+ Structured Concurrency through APIs build on Coroutines and Kotlin Flow

### Configuring in-memory Cache

You can configure in-memory cache with the `MemoryPolicy`:

```kotlin
StoreBuilder
    .from(
        fetcher = nonFlowValueFetcher { api.fetchSubreddit(it, "10").data.children.map(::toPosts) },
        sourceOfTruth = SourceOfTrue.from(
            reader = db.postDao()::loadPosts,
            writer = db.postDao()::insertPosts,
            delete = db.postDao()::clearFeed,
            deleteAll = db.postDao()::clearAllFeeds
        )
    ).cachePolicy(
        MemoryPolicy.builder()
            .setMemorySize(10)
            .setExpireAfterAccess(10.minutes) // or setExpireAfterWrite(10.minutes)
            .build()
    ).build()
```

* `setMemorySize(maxSize: Long)` sets the maximum number of entries to be kept in the cache before starting to evict the least recently used items.
* `setExpireAfterAccess(expireAfterAccess: Duration)` sets the maximum time an entry can live in the cache since the last access, where "access" means reading the cache, adding a new cache entry, and replacing an existing entry with a new one. This duration is also known as **time-to-idle (TTI)**.
* `setExpireAfterWrite(expireAfterWrite: Duration)` sets the maximum time an entry can live in the cache since the last write, where "write" means adding a new cache entry and replacing an existing entry with a new one. This duration is also known as **time-to-live (TTL)**.

Note that `setExpireAfterAccess` and `setExpireAfterWrite` **cannot** both be set at the same time.

### Clearing store entries

You can delete a specific entry by key from a store, or clear all entries in a store.

#### Store with no sourceOfTruth

```kotlin
val store = StoreBuilder
  .fromNonFlow<String, Int> { key: String ->
      api.fetchData(key)
  }.build()
```

The following will clear the entry associated with the key from the in-memory cache:

```kotlin
store.clear("10")
```

The following will clear all entries from the in-memory cache:

```kotlin
store.clearAll()
```

#### Store with sourceOfTruth

When store has a sourceOfTruth, you'll need to provide the `delete` and `deleteAll` functions for `clear(key)` and `clearAll()` to work:

```kotlin
StoreBuilder
    .from(
        fetcher = nonFlowValueFetcher { api.fetchData(key) },
        sourceOfTruth = SourceOfTrue.from(
            reader = dao::loadData,
            writer = dao::writeData,
            delete = dao::clearDataByKey,
            deleteAll = dao::clearAllData
        )
    ).build()
```

The following will clear the entry associated with the key from both the in-memory cache and the sourceOfTruth:

```kotlin
store.clear("10")
```

The following will clear all entries from both the in-memory cache and the sourceOfTruth:

```kotlin
store.clearAll()
```
