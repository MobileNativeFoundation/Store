<div align="center">
    <img src="Images/friendly_robot.png" width="120"/>
    <h1 style="font-size:48px">Store5</h1>
</div>

<div align="center">
    <h4>Full documentation can be found on our <a href="https://mobilenativefoundation.github.io/Store/">website</a>!</h4>
</div>

## Concepts

- [Store](https://mobilenativefoundation.github.io/Store/store/store/) is responsible for managing a particular read request
- [MutableStore](https://mobilenativefoundation.github.io/Store/mutable-store/building/overview/) is
  a [Store](https://mobilenativefoundation.github.io/Store/store/store/) that also manages particular write requests and
  coordinates synchronization and conflict resolution
- [SourceOfTruth](https://mobilenativefoundation.github.io/Store/mutable-store/building/implementations/source-of-truth/) persists items
- [Fetcher](https://mobilenativefoundation.github.io/Store/mutable-store/building/implementations/fetcher/) defines how data will be fetched over network
- [Updater](https://mobilenativefoundation.github.io/Store/mutable-store/building/implementations/updater/) defines how local changes will be pushed to network
- [Bookkeeper](https://mobilenativefoundation.github.io/Store/mutable-store/building/implementations/bookkeeper/) tracks metadata of local changes and records
  synchronization failures
- [Validator](https://mobilenativefoundation.github.io/Store/mutable-store/building/implementations/validator/) returns whether an item is valid
- [Converter](https://mobilenativefoundation.github.io/Store/mutable-store/building/implementations/converter/) converts items
  between [Network](https://mobilenativefoundation.github.io/Store/mutable-store/building/generics/network)
  /[Common](https://mobilenativefoundation.github.io/Store/mutable-store/building/generics/common)
  /[SOT](https://mobilenativefoundation.github.io/Store/mutable-store/building/generics/sot) representations

## How To Include In Your Project

### Android

```kotlin
implementation "org.mobilenativefoundation.store:store5:5.0.0-alpha03"
```

### Multiplatform (Common, JVM, Native, JS)

```kotlin
commonMain {
  dependencies {
    implementation("org.mobilenativefoundation.store:store5:5.0.0-alpha03")
  }
}
```

## License

```text
Copyright (c) 2022 Mobile Native Foundation.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
```

