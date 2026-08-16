# store6-compose

Every spelling below is verified against Store `main` @ `6790606d`. Package `org.mobilenativefoundation.store6.compose`. Callers need `@OptIn(ExperimentalStoreApi::class)`.

## Entry points

All four are `@ExperimentalStoreApi` + `@Composable`, compiled in `commonMain`.

| Receiver | Signature | Returns |
| --- | --- | --- |
| `Store<K, V>` | `collectAsState(key, freshness = Freshness.CachedOrFetch, valueEquivalence = { a, b -> a == b })` | `State<StoreResult<V>>` |
| `Flow<StoreResult<V>>` | `collectAsStoreState(initial = StoreResults.loading(), valueEquivalence = { a, b -> a == b })` | `State<StoreResult<V>>` |
| `Store<K, V>` | `collectAsStateWithLifecycle(key, freshness = Freshness.CachedOrFetch, lifecycleOwner = LocalLifecycleOwner.current, minActiveState = Lifecycle.State.STARTED, valueEquivalence = { a, b -> a == b })` | `State<StoreResult<V>>` |
| `Flow<StoreResult<V>>` | `collectAsStoreStateWithLifecycle(initial = StoreResults.loading(), lifecycleOwner = LocalLifecycleOwner.current, minActiveState = Lifecycle.State.STARTED, valueEquivalence = { a, b -> a == b })` | `State<StoreResult<V>>` |

On targets without a UI host that populates `LocalLifecycleOwner`, pass `lifecycleOwner` explicitly (or use `collectAsState` / `collectAsStoreState`).

```kotlin
val result by store.collectAsState(key)
val result by store.collectAsStateWithLifecycle(key)
```

## Four-kind UI

Never merge kinds. Handle every `StoreResult` kind. `Success`, `Failure`, and `ReadResult` do not exist.

| Kind | Fields | UI |
| --- | --- | --- |
| `Loading` | — | no servable value yet |
| `Data` | `value`, `origin`, `age`, `isStale`, `refreshing` | render the value |
| `Revalidated` | `age` | lifecycle signal: value confirmed fresh; nothing new to render |
| `Error` | `error`, `servedStale` | error UI |

```kotlin
when (result) {
    is StoreResult.Loading -> { /* placeholder */ }
    is StoreResult.Data -> { /* value, origin, isStale, refreshing */ }
    is StoreResult.Revalidated -> { /* still fresh; nothing new to render */ }
    is StoreResult.Error -> { /* error, servedStale */ }
}
```

## Skip-equal-`Data`

`StoreResult` types have identity equality (no `equals` override). These two `@ExperimentalStoreApi` helpers apply the same structural rule:

| API | Role |
| --- | --- |
| `Flow<StoreResult<V>>.skipEqualData(valueEquivalence = { a, b -> a == b })` | drops only consecutive structurally-equal `Data` frames |
| `storeResultMutationPolicy(valueEquivalence = { a, b -> a == b })` | the same rule for Compose `State` (`SnapshotMutationPolicy<StoreResult<V>>`) |

Structural compare on `Data`: `origin`, `isStale`, `refreshing`, `value` — **`age` excluded**. `Loading` / `Revalidated` / `Error` always pass.

## Stability conf

Copy the shipped snippet (`store6-compose/stability/store6-stability.conf`) into the **app** module as `store6-stability.conf`:

```
org.mobilenativefoundation.store6.core.*
org.mobilenativefoundation.store6.core.seam.*
```

```kotlin
composeCompiler {
    stabilityConfigurationFiles.add(
        layout.projectDirectory.file("store6-stability.conf"),
    )
}
```

What it changes: those packages compare as stable values (equal content, not equal instance). Strong skipping still works without it.

## Flow vs State

A `State` is a conflated container and can drop events. Event-shaped `Revalidated` / `Error` must collect the `Flow` (optionally with `skipEqualData`):

```kotlin
store.stream(key)
    .skipEqualData()
    .collect { result -> /* four-kind when */ }
```
