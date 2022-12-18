# Store 5

## Why We Made Store

- Modern software needs data representations to be fluid and always available.
- Users expect their UI experience to never be compromised (blocked) by new data loads. Whether an
  application is social, news or business-to-business, users expect a seamless experience both
  online and offline.
- International users expect minimal data downloads as many megabytes of downloaded data can quickly
  result in astronomical phone bills.

Store is a Kotlin library for loading data from remote and local sources.

## Usage

```kotlin
STORE_VERSION = "5.0.0-alpha03"
```

### Android

```groovy
implementation "org.mobilenativefoundation.store:store5:$STORE_VERSION"
```

### Multiplatform (Common, JVM, Native, JS)

```kotlin
commonMain {
    dependencies {
        implementation("org.mobilenativefoundation.store:store5:$STORE_VERSION")
    }
}
```

## License

```text
Copyright (c) 2022 Mobile Native Foundation.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
```
