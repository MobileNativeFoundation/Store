# Store 5

- [ ] TODO(Badges)

Store is a Kotlin Multiplatform library for reading and writing data from and to remote and local sources.

## The Problems:

+ Modern software needs data representations to be fluid and always available.
+ Users expect their UI experience to never be compromised (blocked) by new data loads. Whether an application is
  social, news or business-to-business, users expect a seamless experience both online and offline.
+ International users expect minimal data downloads as many megabytes of downloaded data can quickly result in
  astronomical phone bills.

Store provides a level of abstraction between UI elements and data operations.

## Concepts

A `Market` is a composition of stores and a bookkeeping system. A `Store` interacts with one data source, or a `Persister`.
A `Bookkeeper` tracks when local changes fail to sync with the network data source.

A market always has one bookkeeper. However, Store is flexible and unopinionated on implementation.
An application can have N markets. And a market can have N stores and execute operations in any order.

Typical applications have one market following a singleton pattern. Most of the time a market has two stores: a memory
store bound to a memory cache then a disk store bound to a database.

- [ ] TODO(Concepts)

## Usage

Artifacts are hosted on **Maven Central**.

**Latest version:**

```groovy
def store_version = "5.0.0-alpha01"
```

### Android

```groovy
implementation "com.dropbox.mobile.store:store5:${store_version}"
```

### Multiplatform (Common, JS, Native)

```kotlin
commonMain {
    dependencies {
        implementation("com.dropbox.mobile.store:market:${MARKET_VERSION}")
    }
}
```

## Implementation

- [ ] TODO(Implementation)

## Examples

- [ ] TODO(Examples)

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