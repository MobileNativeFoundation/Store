<div align="center">
    <img src="Images/friendly_robot.png" width="120"/>
    <h1 style="font-size:48px">Store5</h1>
</div>

<div align="center">
    <h4>Full documentation can be found on our <a href="https://mobilenativefoundation.github.io/Store/">website</a>!</h4>
</div>

### Concepts

- [Store](https://mobilenativefoundation.github.io/Store/store/store/) is a typed repository that returns a flow
  of [Data](https://github.com/MobileNativeFoundation/Store/blob/main/store/src/commonMain/kotlin/org/mobilenativefoundation/store/store5/StoreReadResponse.kt#L39)
  /[Loading](https://github.com/MobileNativeFoundation/Store/blob/main/store/src/commonMain/kotlin/org/mobilenativefoundation/store/store5/StoreReadResponse.kt#L34)
  /[Error](https://github.com/MobileNativeFoundation/Store/blob/main/store/src/commonMain/kotlin/org/mobilenativefoundation/store/store5/StoreReadResponse.kt#L51)
  from local and network data sources
- [MutableStore](https://mobilenativefoundation.github.io/Store/mutable-store/building/overview/) is a mutable repository implementation that allows create **(C)**, read **(R)**,
  update **(U)**, and delete **(D)** operations for local and network resources
- [SourceOfTruth](https://mobilenativefoundation.github.io/Store/mutable-store/building/implementations/source-of-truth/) persists items
- [Fetcher](https://mobilenativefoundation.github.io/Store/mutable-store/building/implementations/fetcher/) defines how data will be fetched over network
- [Updater](https://mobilenativefoundation.github.io/Store/mutable-store/building/implementations/updater/) defines how local changes will be pushed to network
- [Bookkeeper](https://mobilenativefoundation.github.io/Store/mutable-store/building/implementations/bookkeeper/) tracks metadata of local changes and records
  synchronization failures
- [Validator](https://mobilenativefoundation.github.io/Store/mutable-store/building/implementations/validator/) returns whether an item is valid
- [Converter](https://mobilenativefoundation.github.io/Store/mutable-store/building/implementations/converter/) converts items
  between [Network](https://mobilenativefoundation.github.io/Store/mutable-store/building/generics/network)
  /[Local](https://mobilenativefoundation.github.io/Store/mutable-store/building/generics/sot)
  /[Output](https://mobilenativefoundation.github.io/Store/mutable-store/building/generics/common) representations

### Including Store In Your Project

#### Android
```kotlin
implementation "org.mobilenativefoundation.store:store5:5.0.0-alpha04"
```

#### Multiplatform (Common, JVM, Native, JS)

```kotlin
commonMain {
  dependencies {
    implementation("org.mobilenativefoundation.store:store5:5.0.0-alpha04")
  }
}
```

### Getting Started

#### Building Your First Store

```kotlin
StoreBuilder
  .from<Key, Network, Output, Local>(fetcher, sourceOfTruth)
  .converter(converter)
  .validator(validator)
  .build(updater, bookkeeper)
```

#### Creating

##### Request

```kotlin
store.write(
  request = StoreWriteRequest.of<Key, Output, Response>(
    key = key,
    value = value
  )
)
```

##### Response

```text
1. StoreWriteResponse.Success.Typed<Response>(response)
```

#### Reading

##### Request

```kotlin
store.stream<Response>(request = StoreReadRequest.cached(key, refresh = false))
```

##### Response

```text
1. StoreReadResponse.Data(value, origin = StoreReadResponseOrigin.Cache)
```

#### Updating

##### Request

```kotlin
store.write(
  request = StoreWriteRequest.of<Key, Output, Response>(
    key = key,
    value = newValue
  )
)
```

##### Response

```text
1. StoreWriteResponse.Success.Typed<Response>(response)
```

#### Deleting

##### Request

```kotlin
store.clear(key)
```

### License

```text
Copyright (c) 2022 Mobile Native Foundation.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
```

