# Cache

> ℹ️ [Store](https://github.com/MobileNativeFoundation/Store) depends on a subset
> of [Guava](https://github.com/google/guava). We have extracted these parts into a shaded Cache
> artifact that is Kotlin Multiplatform compatible.

## Usage

```kotlin
implementation("org.mobilenativefoundation.store:cache:${STORE_VERSION}")
```

## Implementation

### First, model the key

```kotlin
data class Key(
    val id: String
)
```

### Second, model the value

```kotlin
data class Post(
    val title: String
)
```

### Third, build the cache

```kotlin
 val cache = CacheBuilder<Key, Post>()
    .maximumSize(100)
    .expireAfterWrite(1.hour)
    .build()
```

## See Also

Refer to Guava's Cache documentation for all configuration options:
https://github.com/google/guava/wiki/CachesExplained

## License

```text
Copyright (c) 2017 The New York Times Company

Copyright (c) 2010 The Guava Authors

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this library except in 
compliance with the License. You may obtain a copy of the License at

www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an 
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific 
language governing permissions and limitations under the License.
```
