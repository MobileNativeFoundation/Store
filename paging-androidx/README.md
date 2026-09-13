# paging-androidx

AndroidX Paging 3 interop for Store v6. It turns any `Store` into an
`InvalidatingPagingSourceFactory`, so Paging loads read through the store's freshness,
persistence, and overlay seams, and it ships a `RemoteMediator` base class that drives store
freshness from Paging boundary signals. Everything here is `@ExperimentalStoreApi`, over a
`core` API that is not frozen until the beta01 freeze candidate — see
[STABILITY.md](../STABILITY.md).

The module targets the `androidx.paging` 3.5.1 target set: every canonical Store 6 target
except `iosX64`, which `androidx.paging` dropped at 3.4.0-rc01.

## Install

This artifact ships in 6.0.0-alpha01, which is not released yet. Until then, publish `core`
and `paging-androidx` to Maven Local:

```shell
./gradlew :core:publishToMavenLocal :paging-androidx:publishToMavenLocal
```

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
    google()
}

dependencies {
    implementation("org.mobilenativefoundation.store:paging-androidx:6.0.0-SNAPSHOT")
}
```

## First result

```kotlin
val pages =
    articleStore.pagingSourceFactory<ArticlePageKey, ArticlePage, Int, Article> {
        pageKey { paginationKey, loadSize ->
            ArticlePageKey(cursor = paginationKey, limit = loadSize)
        }
        items { page -> page.articles }
        nextKey { _, page -> page.next }
    }

val articles =
    Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = pages,
    ).flow
```

`pageKey`, `items`, and `nextKey` are required; the factory throws `IllegalStateException`
when one is missing. A `null` pagination key identifies the initial page, and every page
parameter that selects a page — cursor and load size included — must appear in the returned
key's canonical ID. The optional doors are `prevKey` (forward-only by default), `freshness`
per `LoadType` (`Freshness.CachedOrFetch` by default), `refreshKey`, `itemsBefore`, and
`itemsAfter`.

Each load consumes one terminal outcome from `Store.stream` and then keeps that stream
collection open as the generation's watcher. A later data frame or an absent-value loading
transition invalidates the paging source, which is how `store.invalidate(key)`,
`clear(key)`, and a namespace watermark reach the screen. Revalidation and error frames do
not invalidate.

## Refresh keys

The default `refreshKey` returns the previous key of the page closest to the anchor, or
`null` when that page has no previous key. A `null` key restarts paging from the initial
page.

The default never returns the closest page's next key. Paging can only append from the page
a refresh loads, so a refresh keyed on the next key drops the anchored page: a forward-only
source has no previous key to prepend it with. Restarting from the initial page costs one
load and keeps the anchored content reachable.

If your pagination keys are invertible — a cursor you can turn into "the page containing
this item" — supply a mapping that targets the anchored page itself:

```kotlin
refreshKey { state ->
    state.anchorPosition
        ?.let { anchor -> state.closestItemToPosition(anchor) }
        ?.let { article -> article.pageCursor }
}
```

Store cannot derive that mapping. A pagination key is opaque to this module.

## Disposal

A generation's watcher keeps its `Store.stream` collection open after the load that created
it returns. That lifetime is separate from the presenter's: the watcher is released by
invalidation, not by the screen that stops collecting the `PagingData` flow. An abandoned
screen must invalidate the factory it holds, or close the store when the store belongs to
the screen.

```kotlin
class ArticleFeedModel(private val store: Store<ArticlePageKey, ArticlePage>) {
    private val pages =
        store.pagingSourceFactory<ArticlePageKey, ArticlePage, Int, Article> { /* ... */ }

    val articles = Pager(PagingConfig(pageSize = 20), pagingSourceFactory = pages).flow

    // Call from the screen's teardown — ViewModel.onCleared, onDispose, or equivalent.
    fun onAbandoned() {
        pages.invalidate()
    }
}
```

`invalidate()` on the factory invalidates every paging source it created and cancels their
watchers. Closing a store the screen owns does the same for every generation over it.

## Remote mediator

`StoreRemoteMediator` drives store freshness from Paging boundary signals. It is a separate
entry point from the paging source factory, and a `Pager` can use either or both. Refresh
invalidates the mapped initial page key and reads it under
`refreshFreshness()` (`Freshness.MustBeFresh` by default). Append and prepend read the
boundary page under `Freshness.CachedOrFetch`. A `null` directional key ends pagination
without reading the store. Typed `StoreException` failures become `MediatorResult.Error`.
Refresh invalidates only the mapped initial page key, so call
`Store.invalidateNamespace(namespace)` before a Paging refresh when the whole query must go
stale.

## Sample

```shell
./gradlew :paging-androidx-sample:run
```

The headless JVM sample in [`paging-androidx/sample`](sample) asserts four scenes: a cold
first page plus an append, a key invalidation that regenerates the first page, a namespace
watermark that reaches a never-fetched page, and an optimistic mutation observed through the
pager as an `OVERLAY` frame and then as the adopted authoritative value.
