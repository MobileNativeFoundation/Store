![FriendlyRepo Logo](https://raw.githubusercontent.com/friendlyrobotnyc/FriendlyRepo/feature/coroutines/Images/friendly_robot_icon.png) 
# Store(4) [![CircleCI](https://circleci.com/gh/friendlyrobotnyc/Core.svg?style=svg)](https://circleci.com/gh/friendlyrobotnyc/Core)

Store is a Kotlin library for effortless data loading.

### The Problems:

+ Modern software needs data representations to be fluid and always available.
+ Users expect their UI experience to never be compromised (blocked) by new data loads. Whether an application is social, news, or business-to-business, users expect a seamless experience both online and offline.
+ International users expect minimal data downloads as many megabytes of downloaded data can quickly result in astronomical phone bills.

A Store is a class that simplifies fetching, sharing, storage, and retrieval of data in your application. A Store is similar to the Repository pattern [[https://msdn.microsoft.com/en-us/library/ff649690.aspx](https://msdn.microsoft.com/en-us/library/ff649690.aspx)] while exposing an API built with [Coroutines](https://kotlinlang.org/docs/reference/coroutines-overview.html) that adheres to a unidirectional data flow.

Store provides a level of abstraction between UI elements and data operations.

### Overview

A Store is responsible for managing a particular data request. When you create an implementation of a Store, you provide it with a `Fetcher`, a function that defines how data will be fetched over network. You can also define how your Store will cache data in-memory and on-disk, as well as how to parse it. Since Store returns your data as an `Observable`, threading is a breeze! Once a Store is built, it handles the logic around data flow, allowing your views to use the best data source and ensuring that the newest data is always available for later offline use. Stores can be customized to work with your own implementations or use our included middleware.

Store leverages multiple request throttling to prevent excessive calls to the network and disk cache. By utilizing Store, you eliminate the possibility of flooding your network with the same request while adding two layers of caching (memory and disk).

### How to include in your project

###### Include gradle dependency

```
implementation 'com.nytimes.android:store3:3.1.0'
```

###### Set the source & target compatibilities to `1.8`
Starting with Store 3.0, `retrolambda` is no longer used. Therefore to allow support for lambdas the Java `sourceCompatibility` and `targetCompatibility` need to be set to `1.8`

```
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
FlowStoreBuilder
                .fromNonFlow<String, List<Post>, List<Post>> {
                    api.fetchSubreddit(it, "10").data.children.map(::toPosts)
                }
                .persister(reader = db.postDao()::loadPosts,
                        writer = db.postDao()::insertPosts,
                        delete = db.postDao()::clearFeed)
                .build()        
```

With the above setup you have:
+ In-memory caching for rotation
+ Disk caching for when users are offline
+ Throttling of 
+ Rich API to ask for data whether you want cached, new or a stream of future data updates.

And now for the details:

### Creating a Store

You create a Store using a builder. The only requirement is to include a `Fetcher<ReturnType, KeyType>` that returns a `Flow<ReturnType>` and has a single method `fetch(key)`


```kotlin
val store = FlowStoreBuilder<Article, Integer> 
        .from{articleId -> api.getArticle(articleId)} // api returns Flow<Article>
        .open();
```

Stores use generic keys as identifiers for data. A key can be any value object that properly implements `toString()`, `equals()` and `hashCode()`. When your `Fetcher` function is called, it will be passed a particular Key value. Similarly, the key will be used as a primary identifier within caches (Make sure to have a proper `hashCode()`!!).

### Our Key implementation - Barcodes
For convenience, we included our own key implementation called a `BarCode`. `Barcode` has two fields `String key` and `String type`

```kotlin
val barcode =  BarCode("Article", "42")
```


### Public Interface - Get, Fetch, Stream, GetRefreshing

```kotlin
 lifecycleScope.launchWhenStarted {
  val article = store.get(barCode);
 }
```

The first time you call to `suspend store.get(barCode)`, the response will be stored in an in-memory cache. All subsequent calls to `store.get(barCode)` with the same Key will retrieve the cached version of the data, minimizing unnecessary data calls. This prevents your app from fetching fresh data over the network (or from another external data source) in situations when doing so would unnecessarily waste bandwidth and battery. A great use case is any time your views are recreated after a rotation, they will be able to request the cached data from your Store. Having this data available can help you avoid the need to retain this in the view layer.


So far our Store’s data flow looks like this:
![Simple Store Flow](https://github.com/nytm/Store/blob/feature/rx2/Images/store-1.jpg)


By default, 100 items will be cached in memory for 24 hours. You may pass in your own memory policy to override the default policy.


### Busting through the cache

Alternatively you can call `store.fetch(barCode)` to get an `suspended result` that skips the memory (and optional disk cache).


Fresh data call will look like: `store.fetch()`
![Simple Store Flow](https://github.com/nytm/Store/blob/feature/rx2/Images/store-2.jpg)


A good use case is overnight background updates use `fetch()` to make sure that calls to `store.get()` will not have to hit the network during normal usage. Another good use case for `fetch()` is when a user wants to pull to refresh.

Calls to both `fetch()` and `get()` emit one value or throw an error.


### Stream
For real-time updates, you may also call `store.stream()` which returns an `Flow<T>` that emits each time a new item is returned from your store. You can think of stream as a way to create reactive streams that update when you db or memory cache updates

example calls:
```kotlin
lifecycleScope.launchWhenStarted {
        pipeline.stream(StoreRequest.cached(3, refresh = false)) //will get cached value followed by any fresh values, refresh will also trigger network call
        .collect{  }
         
         pipeline.stream(StoreRequest.fresh(3) //skip cache do not pass go, go directly to fetcjer
        .collect{  }
}
```

### Inflight Debouncer

To prevent duplicate requests for the same data, Store offers an inflight debouncer. If the same request is made as a previous identical request that has not completed, the same response will be returned. This is useful for situations when your app needs to make many async calls for the same data at startup or when users are obsessively pulling to refresh. As an example, The New York Times news app asynchronously calls `ConfigStore.get()` from 12 different places on startup. The first call blocks while all others wait for the data to arrive. We have seen a dramatic decrease in the app's data usage after implementing this inflight logic.


//TODO UPDATE THE REST :-)



### Disk Caching

Stores can enable disk caching by passing a `Persister` into the builder. Whenever a new network request is made, the Store will first write to the disk cache and then read from the disk cache.






```kotlin
FlowStoreBuilder
                .fromNonFlow<String, List<Post>, List<Post>> {
                    api.fetchSubreddit(it, "10").data.children.map(::toPosts)
                }
                .persister(reader = db.postDao()::loadPosts,
                        writer = db.postDao()::insertPosts,
                        delete = db.postDao()::clearFeed)
                .build()        
```

Stores don’t care how you’re storing or retrieving your data from disk. As a result, you can use Stores with object storage or any database (Realm, SQLite, CouchDB, Firebase etc). Technically, there is nothing stopping you from implementing an in memory cache for the “persister” implementation and instead have two levels of in memory caching--one with inflated and one with deflated models, allowing for sharing of the “persister” cache data between stores.




If using SQLite we recommend working with [Room](https://developer.android.com/topic/libraries/architecture/room). If you are not using Room, a `Flow` can be created with `SimplePersisterAsFlowable`


The above builder is how we recommend working with data on Android. With the above setup you have:
+ Memory caching with with TTL & Size policies
+ Disk caching with simple integration with Room
+ In-flight request management
+ Ability to get cached data or bust through your caches (`get()` vs. `fetch()`)
+ Ability to listen for any new emissions from network (stream)
+ Structured Concurrency through apis build on Coroutines and Kotlin Flow


### Artifacts Coming Soon

**CurrentVersion = 4.0.0Alpha**
