# Store 5 to Store 6 translation tables

Every spelling below is verified against Store `main` @ `c67a94ed`. Rows marked "nearest translation" are intent translations, not behavioral equivalents. The differences are stated in the row.

## Imports

| Store 5 | Store 6 |
| --- | --- |
| `org.mobilenativefoundation.store.store5.*` | `org.mobilenativefoundation.store6.core.*` (core), `org.mobilenativefoundation.store6.core.seam.*` (expert seams), `org.mobilenativefoundation.store6.mutations.*` (experimental writes) |
| `org.mobilenativefoundation.store.store5.impl.extensions.fresh` | Does not exist. Use `get(key, Freshness.MustBeFresh)`. |

## Builder

Store 5:

```kotlin
val store = StoreBuilder
    .from(fetcher = Fetcher.of { key -> api.fetch(key) }, sourceOfTruth = sot)
    .build()
```

Store 6:

```kotlin
val store = store<UserKey, User> {
    fetcher { key -> api.fetch(key.id) }   // the one required input
}
```

| Store 5 builder setting | Store 6 |
| --- | --- |
| `Fetcher.of { }` | `fetcher { key -> value }` (success-or-throw sugar) |
| `Fetcher.ofResult { }` | `fetcherOfResult { key -> FetcherResult }` with the full result vocabulary: `Success(value, etag)`, `Error(cause)`, `NotModified(etag)` (emits `Revalidated`), `Deleted` (clears without refetch) |
| Fallback fetcher chains | No engine support. Store performs zero retries and no fallback chain. Compose them inside your fetcher. |
| `sourceOfTruth = SourceOfTruth.of(reader, writer, delete, deleteAll)` | `persistence(sot)` where `sot` implements the seam interface `SourceOfTruth<K, V>` (`reader(key): Flow<V?>`, `write(key, value)`, `delete(key)`, `deleteNamespace(namespace)`, `deleteAll()`). `persistence` and the interface are `@ExperimentalStoreApi`, and implementing the interface additionally requires `DelicateStoreApi` opt-in. Prefer the `store6-room`/`store6-sqldelight` adapters. |
| `validator(Validator.by { ... })` | No per-item validity hook. Use per-call `Freshness` (usually `MaxAge`) plus `invalidate*`. The experimental `FreshnessValidator` seam is a read planner, not a validity check. |
| `scope(...)` | No counterpart. The store owns its lifecycle. Release it with `close()`. After close, operations fail with `IllegalStateException("Store is closed.")`. |
| `cachePolicy(...)` / `disableCache()` | No TTL cache policy. `maxIdleKeys(count)` (default 128) bounds quiescent per-key engine residency, and `0` destroys each engine at quiescence. Eviction discards derived state only. Durable rows, stale marks, and watermarks survive. |

Keys change shape. Store 5 accepts any non-null key. A Store 6 key implements `StoreKey` and supplies `namespace` and `canonicalId()`, which together form the durable identity.

```kotlin
class UserKey(val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")
    override fun canonicalId(): String = id
}
```

## Read requests → Freshness

Store 5 puts cache and fetch choices in `StoreReadRequest`. Store 6 puts a `Freshness` policy on each call. Calls with different policies still share one in-flight fetch per key.

| Store 5 request | Store 6 translation |
| --- | --- |
| `StoreReadRequest.cached(key, refresh = false)` | Nearest translation: `stream(key)` or `get(key)` with default `Freshness.CachedOrFetch`. Still fetches when nothing is local, but may also serve-and-background-revalidate invalidated or metadata-less local residence. |
| `StoreReadRequest.fresh(key)` | Nearest translation: `Freshness.MustBeFresh`, which withholds residence and blocks for a fresh fetch. Store 5 could emit `NoNewData` on an empty fetcher flow. Store 6 has no empty-flow outcome: a failed `MustBeFresh` read emits one `StoreResult.Error` and completes the flow (`stream`) or throws `StoreException` (`get`). |
| `StoreReadRequest.localOnly(key)` | `Freshness.LocalOnly`: never invokes the fetcher, probes persistence once on a memory miss, reports `StoreError.Missing` when nothing is local. |
| `StoreReadRequest.fresh(key, fallBackToSourceOfTruth = true)` | Nearest policy: `Freshness.StaleIfError`, which prefers fresh and falls back to the stale local value when the fetch fails. Not an exact equivalence. |
| `StoreReadRequest.cached(key, refresh = true)` | No single policy. For caller-initiated refresh, call `invalidate(key)` and keep collecting the default stale-while-revalidate stream. |
| `StoreReadRequest.skipMemory(key, refresh)` | No equivalent. Store 6 does not expose per-call skipping of storage layers. |

## Read responses → StoreResult

| Store 5 | Store 6 |
| --- | --- |
| `Initial` / `Loading` | `Loading`: emitted when demand exists and no value is servable. No separate `Initial` kind. |
| `Data(value, origin)` | `Data(value, origin, age, isStale, refreshing)` |
| `NoNewData` | No analog (it meant a Store 5 fetcher flow completed without data). |
| `Error.Exception` / `Error.Message` / `Error.Custom` | `Error(error: StoreError, servedStale)`. `StoreError` has six variants: `Fetch`, `Persistence`, `Conversion`, `FreshnessUnsatisfiable`, `Conflict`, `Missing`. Each variant carries a `message`, the sealed base does not, so match exhaustively. `servedStale` is true when a stale resident was served and its refresh then failed under a stale-tolerant policy. |
| No analog | `Revalidated(age)`: the not-modified result of a conditional fetch. Clears staleness without emitting redundant `Data`. Handle it in every exhaustive `when`. |

Origins:

| Store 5 origin | Store 6 `Origin` |
| --- | --- |
| `Cache` | `MEMORY` |
| `SourceOfTruth` | `SOT` |
| `Fetcher(name)` | `FETCHER` |
| `Initial` | No analog. Store 6 has no `Initial` response kind, and `Loading` carries no origin. |
| No analog | `OVERLAY`: an optimistic projection above committed data, visible on `stream` only. The mutation engine installs one, and core's experimental `overlay(...)` builder seam can too. |

Helpers `requireData()`, `dataOrNull()`, and `throwIfError()` do not carry over. The two doors replace them. `get` returns a value or throws `StoreException`. `stream` emits results and never throws retrieval failures: a `Freshness.MustBeFresh` initial-cycle failure emits one error and completes the flow, and every other failure leaves the flow live.

`StoreException` exposes the structured failure as `error: StoreError` and the underlying failure through the standard exception `cause` (nullable). Store 5 callers that caught the fetcher's own exception type (for example `IOException`) from `fresh` must catch `StoreException` and inspect `error` or `cause` instead.

## Maintenance

| Store 5 | Store 6 |
| --- | --- |
| `clear(key)` used to force a refresh | `invalidate(key)`: marks stale, keeps the value visible, triggers exactly one refresh for live streams. Also `invalidateNamespace(namespace)`, `invalidateAll()`. Stale marks are durable across restarts and do not require a resident value: a key is durably stale when its mark or a namespace/global watermark is newer than its last successful fetch, so invalidating a not-yet-fetched key is valid and applies to future reads. |
| `clear(key)` / `clearAll()` used to remove data | `clear(key)`, `clearNamespace(namespace)`, `clearAll()`: destructively remove values and their per-key freshness records. A post-clear stream never replays pre-clear data. |
| (no counterpart) | `close()`: releases the store. Subsequent operations throw `IllegalStateException`. |

All `invalidate*` and `clear*` operations are suspending and can throw `StoreException` when persisting the mark or performing durable deletion fails. `close()` is a plain function.

Decision test: if the value is wrong to show, clear it. If it is merely imperfect or old, invalidate it.

## Worked port

The Store 5 file below is a common screen shape: builder with source of truth, a 5-minute `Validator`, `cached(refresh = true)` for the screen subscription, `fresh` for pull-to-refresh, `clearAll` on sign-out.

```kotlin
// Store 5 (before)
private val store = StoreBuilder
    .from(
        fetcher = Fetcher.of { id: String -> api.fetchUser(id) },
        sourceOfTruth = SourceOfTruth.of(
            reader = { id -> dao.observeUser(id) },
            writer = { _, user -> dao.upsert(user) },
            delete = { id -> dao.delete(id) },
            deleteAll = { dao.deleteAll() },
        ),
    )
    .validator(Validator.by { user -> nowMillis() - user.updatedAtMillis < 5.minutes.inWholeMilliseconds })
    .build()

fun observeUser(id: String): Flow<UserUiState> =
    store.stream(StoreReadRequest.cached(key = id, refresh = true)).map { response ->
        when (response) {
            is StoreReadResponse.Initial, is StoreReadResponse.Loading -> UserUiState.Loading
            is StoreReadResponse.Data -> UserUiState.Loaded(response.value, response.origin is StoreReadResponseOrigin.Cache)
            is StoreReadResponse.NoNewData -> UserUiState.Loading
            is StoreReadResponse.Error.Exception -> UserUiState.Failed(response.error.message ?: "Unknown error")
            is StoreReadResponse.Error.Message -> UserUiState.Failed(response.message)
            is StoreReadResponse.Error.Custom<*> -> UserUiState.Failed("Unknown error")
        }
    }

suspend fun refreshUser(id: String): User = store.fresh(id)
suspend fun onSignOut() = store.clearAll()
```

The Store 6 port. It translates intent and is not row-for-row behaviorally identical. Differences are noted inline:

```kotlin
// Store 6 (after)
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.store

class UserKey(val id: String) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("users")
    override fun canonicalId(): String = id
}

// Implementing the seam requires both opt-ins. Validate custom implementations with the
// store6-testing contract kit; prefer the store6-room/store6-sqldelight adapters when the app
// already has a database.
@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
private class UserDaoSourceOfTruth(private val dao: UserDao) : SourceOfTruth<UserKey, User> {
    override fun reader(key: UserKey): Flow<User?> = dao.observeUser(key.id)
    override suspend fun write(key: UserKey, value: User) = dao.upsert(value)
    override suspend fun delete(key: UserKey) = dao.delete(key.id)
    // This store has a single namespace, so both scopes clear the same table.
    override suspend fun deleteNamespace(namespace: StoreNamespace) = dao.deleteAll()
    override suspend fun deleteAll() = dao.deleteAll()
}

class UserRepository(api: UserApi, dao: UserDao) {
    @OptIn(ExperimentalStoreApi::class)
    private val store = store<UserKey, User> {
        fetcher { key -> api.fetchUser(key.id) }   // retries and fallbacks belong inside this block
        persistence(UserDaoSourceOfTruth(dao))
    }

    // The Store 5 Validator bounded staleness at 5 minutes; MaxAge puts that bound on the call.
    // Difference: age is measured from commit time, not the value's own timestamp field, and an
    // over-age resident is withheld until the fetch succeeds rather than emitted alongside it.
    fun observeUser(id: String): Flow<UserUiState> =
        store.stream(UserKey(id), Freshness.MaxAge(5.minutes))
            .map { result ->
                when (result) {
                    is StoreResult.Loading -> UserUiState.Loading
                    is StoreResult.Data -> UserUiState.Loaded(
                        user = result.value,
                        fromCache = result.origin == Origin.MEMORY,
                    )
                    // The conditional fetch confirmed the current value is fresh; nothing new to render.
                    is StoreResult.Revalidated -> null
                    is StoreResult.Error -> UserUiState.Failed(result.error.describe())
                }
            }
            .filterNotNull()

    // Store 5 store.fresh(id): block for a network round trip or fail.
    suspend fun refreshUser(id: String): User = store.get(UserKey(id), Freshness.MustBeFresh)

    // Sign-out data is wrong to show afterward, so clear rather than invalidate.
    suspend fun onSignOut() = store.clearAll()

    // No builder scope(...): the owner releases the store explicitly.
    fun close() = store.close()
}

private fun StoreError.describe(): String = when (this) {
    is StoreError.Fetch -> message
    is StoreError.Persistence -> message
    is StoreError.Conversion -> message
    is StoreError.FreshnessUnsatisfiable -> message
    is StoreError.Conflict -> message
    is StoreError.Missing -> message
}
```

If the screen must refetch on every subscription regardless of age (exact `cached(refresh = true)` behavior), keep the default `stream(key)` and have the refresh action call `invalidate(key)`. There is no single-policy equivalent.
