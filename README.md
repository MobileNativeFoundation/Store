# Store 5, a Kotlin Multiplatform Library for Building Network-Resilient Applications

## Problems

- Modern software needs data representations to be fluid and always available.
- Users expect their UI experience to never be compromised (blocked) by new data loads. Whether an application is
  social, news or business-to-business, users expect a seamless experience both online and offline.
- International users expect minimal data downloads as many megabytes of downloaded data can quickly result in
  astronomical phone bills.

## Concepts

1. `Market` is a composition of stores and bookkeeping systems.
2. `Store` interacts with a local data source through a `Persister`.
3. `Bookkeeper` tracks local changes and reports failures to sync with the network.
4. An `App` generally has one `Market` following a singleton pattern. A `Market` generally has two `Store(s)`:
   one bound to a memory cache then one bound to a database. However, an `App` can have _N_ `Market(s)`. And a `Market`
   can have _N_ `Store(s)` and execute operations
   in any order.

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

## Usage

```kotlin
STORE_VERSION = "5.0.0-alpha01"
```

### Android

```groovy
implementation "org.mobilenativefoundation.store:store5:$STORE_VERSION"
```

### Multiplatform (Common, JS, Native)

```kotlin
commonMain {
    dependencies {
        implementation("org.mobilenativefoundation.store:store5:$STORE_VERSION")
    }
}
```

## Implementation

### 2. Create a Memory Store

```kotlin
fun <Key : Any, Input : Any, Output : Any> buildMemoryLruCacheStore(
    memoryLruCache: MemoryLruCache
): Store<Key, Input, Output> = Store.by(
    reader = { key, _ -> memoryLruCache.read(serializer.encodeToString(key)) },
    writer = { key, input -> memoryLruCache.write(serializer.encodeToString(key)) },
    deleter = { key -> memoryLruCache.delete(serializer.encodeToString(key)) },
    clearer = { memoryLruCache.deleteAll() }
)
```

## License

```text
Copyright (c) 2022 Mobile Native Foundation.

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