# Fallback Mechanisms

`Superstore` and `Warehouse` handle data retrieval and fallback mechanisms when a primary data source fails
or returns an error. Proposed in [#540](https://github.com/MobileNativeFoundation/Store/issues/540).

## Warehouse

Represents hardcoded data or any other fallback data source. It provides a simple API for fetching
fallback data.

## Superstore

Coordinates a `Store` and `List<Warehouse>`. The `Superstore` will first attempt to fetch data from
the `Store`. If the `Store` fails or returns an error, the `Superstore` will retrieve data from a `Warehouse`.

## Sample

### Setup

#### Build Your Warehouses

```kotlin
class SecondaryApi : Warehouse<PageType, Page> {
    override suspend fun get(key: PageType): Page? = when (key) {
        is PageType.Account -> fetchAccountPage()
        is PageType.Upgrade -> fetchUpgradePage()
    }
}
```

```kotlin
class HardcodedPages : Warehouse<PageType, Page> {
    override suspend fun get(key: PageType): Page = when (key) {
        is PageType.Account -> hardcodedAccountPage()
        is PageType.Upgrade -> hardcodedUpgradePage()
    }
}
```

#### Build a Store

[See Store documentation](https://mobilenativefoundation.github.io/Store/store/store/).

#### Build a Superstore

```kotlin
val superstore = Superstore.from(
    store = store,
    warehouses = listOf(secondaryApi, hardcodedPages)
)
```

### Usage

```kotlin
superstore.get(PageType.Account).collect { response ->
    when (response) {
        is SuperstoreResponse.Data -> handleData()
        SuperstoreResponse.Loading -> handleLoading()
    }
}
```

```kotlin
val state = superstore.get(PageType.Account).stateIn(scope)
```

```kotlin
val accountPage = superstore.get(PageType.Account).firstData()
```
