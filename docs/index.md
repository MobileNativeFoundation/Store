# Store5

## Why We Made Store

- Modern software needs data representations to be fluid and always available
- Users expect their UI experience to never be compromised (blocked) by new data loads. Whether an
  application is social, news or business-to-business, users expect a seamless experience both
  online and offline
- International users expect minimal data downloads as many megabytes of downloaded data can quickly
  result in astronomical phone bills

## Concepts

1. `Store` is responsible for managing a particular read request
2. `MutableStore` is a `Store` that also manages particular write requests and coordinates
   synchronization and conflict resolution
3. `SourceOfTruth` persists `Item(s)`
4. `Fetcher` defines how data will be fetched over network
5. `Updater` defines how local changes will be pushed to network
6. `Bookkeeper` tracks metadata of local changes and records synchronization failures
7. `Validator` returns whether an `Item` is valid
8. `Converter` converts `Item(s)` between `Network`/`Common`/`SOT` representations

## How To Include In Your Project

### Android

```groovy
implementation "org.mobilenativefoundation.store:store5:5.0.0-beta02"
```

### Multiplatform (Common, JVM, Native, JS)

```kotlin
commonMain {
    dependencies {
        implementation("org.mobilenativefoundation.store:store5:5.0.0-beta02")
    }
}
```

## See Also

- [Google Offline-First Guidance](https://developer.android.com/topic/architecture/data-layer/offline-first)
- [https://github.com/google/guava/wiki/CachesExplained](https://github.com/google/guava/wiki/CachesExplained)

## License

```text
Copyright (c) 2022 Mobile Native Foundation.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
```
