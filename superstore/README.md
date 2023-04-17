# Fallback Mechanisms

`Superstore` and `Warehouse` handle data retrieval and fallback mechanisms when a primary data
source fails
or returns an error. Proposed in [#540](https://github.com/MobileNativeFoundation/Store/issues/540).

## Warehouse

Represents hardcoded data or any other fallback data source. It provides a simple API for fetching
fallback data.

## Superstore

Coordinates a `Store` and `List<Warehouse>`. The `Superstore` will first attempt to fetch data from
the `Store`. If the `Store` fails or returns an error, the `Superstore` will retrieve data from
a `Warehouse`.

## Sample

### Setup

#### Build Your Warehouses

```kotlin
class SecondaryApi : Warehouse<PageType, Page> {
    override val name = "SecondaryApi"
    override suspend fun get(key: PageType): WarehouseResponse<Page> = when (key) {
        is PageType.Account -> fetchAccountPage(key)
        is PageType.Upgrade -> fetchUpgradePage(key)
    }
}
```

```kotlin
class HardcodedPages : Warehouse<PageType, Page> {
    override val name = "HardcodedPages"
    override suspend fun get(key: PageType): WarehouseResponse.Data<Page> = when (key) {
        is PageType.Account -> hardcodedAccountPage(key)
        is PageType.Upgrade -> hardcodedUpgradePage(key)
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

#### Create a Request

```kotlin
val request = StoreReadRequest.fresh(key)
```

#### Stream from Superstore

```kotlin
superstore.stream(request).collect { response ->
    when (response) {
        is SuperstoreResponse.Data -> handleData()
        SuperstoreResponse.Loading -> handleLoading()
        SuperstoreResponse.NoNewData -> handleNoNewData()
    }
}
```

```kotlin
val state = superstore.stream(request).stateIn(scope)
```

```kotlin
val accountPage = superstore.stream(request).firstData()
```
