# Store 5 components → Store 6, row by row

Store 5 documents eight components: Store, Fetcher, SourceOfTruth, Converter, Validator, MutableStore, Updater, and Bookkeeper. Seven rows cover them because `MutableStore` and `Updater` move together.

| Store 5 component | Store 6 replacement |
| --- | --- |
| `Store` | `Store<K, V>` with `stream(key, freshness)`, suspending `get(key, freshness)`, namespace-aware `invalidate*`/`clear*`, and explicit `close()` |
| `Fetcher` | `fetcher { }`, `fetcherOfResult { }`, or the experimental seam `Fetcher` (receives conditional-request ETags). Last registration wins across all three. |
| `SourceOfTruth` | The seam `SourceOfTruth<K, V>` installed via `persistence(...)`, or a `room`/`sqldelight` adapter |
| `Converter` | **No direct analog.** Mapping lives in fetcher and persistence callbacks. |
| `Validator` | Native per-call `Freshness`, durable invalidation, and the expert `FreshnessValidator` read-planning seam |
| `MutableStore` + `Updater` | `mutationStore` plus typed `mutate`. **Updater has no direct analog:** its transport job moves to an app-owned `MutationServer` invoked by foreground `drain`. See [mutations.md](mutations.md). |
| Failed-sync `Bookkeeper` | **No direct analog.** Durable mutation-journal records and inspection replace the job. |

## Store → Store

Store 5 centers reads on `stream(StoreReadRequest)`. Store 6 uses `stream(key, freshness)` and adds suspending `get(key, freshness)`. One failure channel: `stream` emits `StoreResult.Error` and never throws retrieval failures (a `MustBeFresh` initial-cycle failure emits one error and completes the flow). `get` returns a value or throws `StoreException` and never emits a wrapper.

`clear(key)`/`clearAll()` become two families. `invalidate*` marks stale and preserves values. `clear*` destructively removes values and their per-key freshness records. Namespace and global stale watermarks are conservative and are not reset by clear operations.

## Fetcher → fetcher, fetcherOfResult, or the seam

Store 5's `FetcherResult.Data` and three error shapes become `FetcherResult.Success(value, etag)` and `FetcherResult.Error(cause)`. Store 6 adds `NotModified(etag)`, which produces one `StoreResult.Revalidated`, and `Deleted`, which clears the resident value without an automatic refetch.

No engine-level fallback chain exists. Store performs zero retries. Compose retry, backoff, or fallback endpoints inside your fetcher.

## SourceOfTruth → the persistence seam

Store 5's `SourceOfTruth<Key, Local, Output>` had separate local and output types, with a `Converter` bridging the fetcher's network type. The Store 6 seam is `SourceOfTruth<K, V>` with one value type and a nullable-row reader.

The contract: `reader(key)` immediately first-emits the current row or `null`, stays live, and publishes changes made through that instance. Mutations provide read-your-writes on normal return and are exception-atomic, including cancellation. `deleteNamespace` is new. Validate implementations with the `testing` contract kit.

## Converter → callbacks

No converter seam exists. A store is typed on one value `V`: map network payloads to `V` inside the fetcher, and map `V` to and from database rows inside the persistence adapter's callbacks. The conversion still exists. It is owned at the boundary where the representation changes.

## Validator → native freshness

Store 5's `Validator.isValid(item)` asked one per-item question. Store 6 plans each read from resident availability, freshness metadata, durable staleness, and one of five per-call policies (`CachedOrFetch`, `MaxAge`, `MustBeFresh`, `StaleIfError`, `LocalOnly`). Every `StoreResult.Data` reports `isStale`, and `invalidate*` records staleness directly.

The experimental `FreshnessValidator` seam is not a per-item validity hook: its pure `plan(context)` returns a fetch plan (`Skip`, `Fetch`, or `Conditional`) for one coherent read snapshot. Most applications should use the native policies.

## Bookkeeper → the journal, with a name collision

Store 5's `Bookkeeper` recorded failed-sync timestamps so later reads could detect unsynced local changes. No Store 6 component has that job. The mutation journal replaces the system with durable intents, attempt generations, acknowledgement progress, normalized failures, and retirement, inspected through `pending(key)`, `pendingWrites()`, and `deadLetters()`.

Name collision: Store 6 core also has a type named `Bookkeeper`, but it records freshness metadata, per-key stale marks, and namespace/global watermarks. It does not track failed write synchronization.

## Not components in Store 6

Memory-cache configuration: `maxIdleKeys` (default 128) bounds quiescent per-key engine residency. Eviction discards derived engine state, never durable rows, metadata, stale marks, or watermarks. Builder `scope(...)`: no counterpart. Stores own their work and release it through `close()`.
