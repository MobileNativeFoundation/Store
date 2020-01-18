# Cache

**Cache** is an in-memory caching library written in 100% Kotlin. Primary features include:

* Time-based evictions (expirations)
* Size-based evictions
* Cache loader

While this library was originally written to be used by [Store 4](https://github.com/dropbox/Store) for in-memory caching, it can also be used for general-purpose caching.

### Download

Artifact is hosted on **Maven Central**.

###### Latest version:

Latest version of this library is aligned with [Store 4](https://github.com/dropbox/Store).

###### Add the dependency to your `build.gradle`:

```groovy
implementation 'com.dropbox.mobile.store:cache4:${store_version}'
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

### Writing and Reading Cache Entries

**Cache** is a key-value based store with simple APIs.

To create a new `Cache` instance using `Long` for the key and `String` for the value:

```kotlin
val cache = Cache.Builder.newBuilder().build<Long, String>()
```

To start writing entries to the cache:

```kotlin
cache.put(1, "dog")
cache.put(2, "cat")
```

To read a cache entry by key:

```kotlin
cache.get(1) // returns "dog"
cache.get(2) // returns "cat"
cache.get(3) // returns null
```

To overwrite an existing cache entry:

```kotlin
cache.put(1, "dog")
cache.put(1, "bird")
cache.get(1) // returns "bird"
```

### Cache Loader

**Cache** provides an API for get cached value by key and using the provided `loader: () -> Value` lambda to compute and cache the value automatically if none exists.

```kotlin
val cache = Cache.Builder.newBuilder().build<Long, User>()

val userId = 1L
val user = cache.get(userId) {
    fetchUserById(userId) // potentially expensive call
}

// value succefully computed by the loader will be cached automatically
assertThat(user).isEqualTo(cache.get(userId))
```

_Note that `loader` is executed on the caller's thread. Concurrent calls from multiple threads using the same `key` will be **blocked**. Assuming the 1st call successfully computes a new value, none of the `loader` from the other calls will be executed and the cached value computed by the first loader will be returned for those calls._

Any exceptions thrown by the `loader` will be propagated to the caller of this function.

### Expirations and Evictions

By default **Cache** has an unlimited number of entries which never expire. But a cache can be configured to support both **time-based expirations** and **size-based evictions**.

#### Time-based Expiration

Expiration time can be specified for entries in the cache.

##### Expire After Access

To set the maximum time an entry can live in the cache since the last access (also known as **time-to-idle**), where "access" means **reading the cache**, **adding a new cache entry**, or **replacing an existing entry with a new one**:

```kotlin
val cache = Cache.Builder.newBuilder()
            .expireAfterAccess(24, TimeUnit.HOURS)
            .build<Long, String>()
```

An entry in this cache will be removed if it has not been read or replaced **after 24 hours** since it's been written into the cache.

##### Expire After Write

To set the maximum time an entry can live in the cache since the last write (also known as **time-to-live**), where "write" means **adding a new cache entry** or **replacing an existing entry with a new one**:

```kotlin
val cache = Cache.Builder.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build<Long, String>()
```

An entry in this cache will be removed if it has not been replaced **after 30 minutes** since it's been written into the cache.

_Note that cache entries are **not** removed immediately upon expiration at exact time. Expirations are checked in each interaction with the `cache`._

### Size-based Eviction

To set the the maximum number of entries to be kept in the cache:

```kotlin
val cache = Cache.Builder.newBuilder()
            .maximumCacheSize(100)
            .build<Long, String>()
```

Once there are more than **100** entries in this cache, the **least recently used one** will be removed, where "used" means **reading the cache**, **adding a new cache entry**, or **replacing an existing entry with a new one**.

### Deleting Cache Entries

Cache entries can also be deleted explicitly.

To delete a cache entry for a given key:

```kotlin
val cache = Cache.Builder.newBuilder().build<Long, String>()
cache.put(1, "dog")

cache.invalidate(1)

assertThat(cache.get(1)).isNull()
```

To delete all entries in the cache:

```kotlin
cache.invalidateAll()
```

### Unit Testing Cache Expirations

To make it easier for testing logics that depend on cache expirations, `Cache.Builder` provides an API for setting a fake implementation of [Clock] for controlling (virtual) time in tests.

First define a custom `Clock` implementation:

```kotlin
class TestClock(var virtualTimeNanos: Long = -1) : Clock {
    override val currentTimeNanos: Long
        get() = virtualTimeNanos
}
```

Now you are able to test your logic that depends on cache expiration. A test might look like this:

```kotlin
@Test
fun `cache entry gets evicted when expired after write`() {
    private val clock = TestClock(virtualTimeNanos = 0)
    val oneMinute = TimeUnit.MINUTES.toNanos(1)
    val cache = Cache.Builder.newBuilder()
        .clock(clock)
        .expireAfterWrite(oneMinute, TimeUnit.NANOSECONDS)
        .build<Long, String>()

    cache.put(1, "dog")

    // just before expiry
    clock.virtualTimeNanos = oneMinute - 1

    assertThat(cache.get(1))
        .isEqualTo("dog")

    // now expires
    clock.virtualTimeNanos = oneMinute

    assertThat(cache.get(1))
        .isNull()
}
```
