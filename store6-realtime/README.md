# store6-realtime

Maps application-owned realtime frames onto Store operations. The application supplies a
`Flow<RealtimeMessage<K, V>>` (or calls `apply` per frame). This artifact does not open a
WebSocket or SSE connection and does not choose a wire format.

Every public entry point is `@ExperimentalStoreApi`. The Store seams it consumes
(`StoreWriteHandle`, `Store.runtime()`, `Store.invalidate*`, `Store.clear`) are freeze
candidates, not frozen — see [STABILITY.md](../STABILITY.md).

## Install

```kotlin
kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(
                    "org.mobilenativefoundation.store:store6-realtime:6.0.0-SNAPSHOT",
                )
            }
        }
    }
}
```

The module uses Store6's full 12-target convention: Android, JVM, `iosArm64`,
`iosSimulatorArm64`, `iosX64`, `macosArm64`, `watchosArm64`, `tvosArm64`, JS, WasmJS,
`linuxX64`, and `mingwX64`.

## Entry points

- `realtimeBinding(store)` — adopting mode. Requires `store.runtime()`. `Upsert` commits
  through `StoreWriteHandle.apply` then `confirmFresh`. `Unchanged` calls `confirmFresh`.
- `invalidatingRealtimeBinding(store)` — works on any `Store`, including `FakeStore` and
  `MutationStore`. `Upsert` becomes `Store.invalidate(key)`. `Unchanged` is ignored.
- `RealtimeBinding.apply(message)` — one frame, serialized per binding.
- `RealtimeBinding.consume(messages)` — sequential pass until the flow completes.

`realtimeBinding` throws `IllegalArgumentException` when `store.runtime()` is `null`:

```text
realtimeBinding requires an engine-backed Store. store.runtime() is null for FakeStore, decorators, and MutationStore. Use invalidatingRealtimeBinding(store) when adoption is unavailable.
```

`RealtimeBinding` owns no `CoroutineScope` and has nothing to close. Close the bound Store
when the store itself should stop.

## Vocabulary

| Message | Adopting binding | Invalidating binding |
| --- | --- | --- |
| `Upsert(key, value, etag)` | `writeHandle.apply` then `confirmFresh` | `Store.invalidate(key)` |
| `Unchanged(key, etag)` | `writeHandle.confirmFresh` | no-op |
| `Changed(key)` | `writeHandle.markStale` (`Store.invalidate`) | `Store.invalidate(key)` |
| `ChangedNamespace(namespace)` | `Store.invalidateNamespace` | `Store.invalidateNamespace` |
| `ChangedAll` | `Store.invalidateAll` | `Store.invalidateAll` |
| `Deleted(key)` | `Store.clear(key)` | `Store.clear(key)` |

`etag` is written as the new resident tag, including `null`. `confirmFresh` without a
resident value is a no-op.

## Minimal example

```kotlin
val users = store<UserKey, User> {
    fetcher(userFetcher)
}
val pushes = realtimeBinding(users)

pushes.consume(incoming) // Flow<RealtimeMessage<UserKey, User>> from the app transport
```

The executable sample is `./gradlew :store6-realtime-sample:run`.

## Transport, reconnect, and gaps

Reconnect, backoff, and connectivity are host policy. After a gap in which frames may have
been missed, apply `ChangedAll` or `ChangedNamespace` for the affected space before resuming
per-key messages. The binding does not detect gaps.

`consume` applies messages in arrival order and backpressures the flow while a Store
operation is in flight. Cross-key order is preserved. Failures propagate as thrown
`StoreException` or `IllegalStateException` (`Store is closed.`); already-applied messages
stay applied.

An adopting `Upsert` pair is not atomic. Cancelling `apply` after `StoreWriteHandle.apply`
and before `confirmFresh` can leave the value committed without bookkeeping success. A fetch
already in flight is not cancelled; a fetch commit that runs after `apply` is later
source-of-truth authority.

## MutationStore

`MutationStore` withholds `store.runtime()` so `realtimeBinding` cannot adopt through the
write handle. Use `invalidatingRealtimeBinding(mutationStore)`. On reconnect, the host still
calls `MutationStore.drain()` / `drain(key)` to flush the mutation journal. Those are
separate passes.

## Telemetry

`Store.invalidate*` and `Store.clear` produce `KeyEvents` and, when telemetry is configured,
`StoreTelemetry` callbacks. `StoreWriteHandle.apply` emits `KeyEvents.Written(origin = SOT)`.
`confirmFresh` emits no events. An active stream that re-emits after either fires
`StoreTelemetry.onServe` when telemetry is configured. This artifact adds no event vocabulary
and does not decide a wire format.
