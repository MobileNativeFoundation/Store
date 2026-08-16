# Store 6 from Swift

Spellings from committed dumps `store6-core/api/swift/skie/Store6CoreSkie.swift` and `store6-core/api/swift/objc/Store6Core.h` at Store `main` @ `6790606d`. If a name is not here, it does not exist.

## `onEnum(of:)` case sets

SKIE `@frozen __Sealed` enums. Switch with `onEnum(of:)`. An unknown sealed subtype hits `fatalError` inside `onEnum` — a `default` branch is not a safety net.

| Type | Cases |
| --- | --- |
| `StoreResult` | `data`, `error`, `loading`, `revalidated` |
| `Freshness` | `cachedOrFetch`, `localOnly`, `maxAge`, `mustBeFresh`, `staleIfError` |
| `StoreError` | `conflict`, `conversion`, `fetch`, `freshnessUnsatisfiable`, `missing`, `persistence` (frozen for 6.x) |
| `FetcherResult` | `deleted`, `error`, `notModified`, `success` |

```swift
switch onEnum(of: result) {
case .data(let data): /* data.value, .origin, .age, .isStale, .refreshing */
case .error(let failure): switch onEnum(of: failure.error) { /* six StoreError cases */ }
case .loading: break
case .revalidated(let frame): /* frame.age */
}
```

## Suspend ops, `close()`, `stream`

| Op | SKIE | ObjC |
| --- | --- | --- |
| `get(key:freshness:)` | `async throws -> Any` | `get(key:freshness:completionHandler:)` |
| `invalidate(key:)` / `invalidateNamespace(namespace:)` / `invalidateAll()` | `async throws` | matching `…(completionHandler:)` |
| `clear(key:)` / `clearNamespace(namespace:)` / `clearAll()` | `async throws` | matching `…(completionHandler:)` |
| `close()` | synchronous — no async wrapper | `close()` — no completion handler |
| `stream(key:freshness:)` | returns `Kotlinx_coroutines_coreFlow` | same; not a completion-handler op |

`stream` itself is **not** `AsyncSequence`. Wrap the returned `Kotlinx_coroutines_coreFlow` in `SkieSwiftFlow` (`SkieSwiftFlowProtocol` : `AsyncSequence`; public convenience init takes `SkieKotlinFlow<T>`). Iteration is task-scoped; cancellation is wired through the SKIE iterator. Flow elements are erased (`Any` / `AnyObject`) — do not invent a typed `StoreResult` generic if the dump does not give you one.

```swift
let flow = store.stream(key: key, freshness: freshness) // Kotlinx_coroutines_coreFlow
for await result in SkieSwiftFlow(SkieKotlinFlow(flow)) {
    switch onEnum(of: result) { /* four StoreResult cases */ }
}
```

## `int64_t` trap — `Duration` raw vs epoch millis

ObjC export flattens both Kotlin `Duration` and `Long` to `int64_t`. They are not the same unit. Do not pass a bare integer like `5000` as if it were seconds or millis for a `Duration` field.

| Field | `int64_t` means |
| --- | --- |
| `Freshness.MaxAge.notOlderThan` | Kotlin `Duration` **raw representation** (not millis, not seconds) |
| `StoreResult.Data.age` | Kotlin `Duration` **raw representation** |
| `StoreResult.Revalidated.age` | Kotlin `Duration` **raw representation** |
| `StoreMeta.writtenAtEpochMillis` | Unix epoch **milliseconds** |
| `Bookkeeper.recordFailure` `atEpochMillis` | Unix epoch **milliseconds** |
| `WallClock.nowEpochMillis` | Unix epoch **milliseconds** |

Construct `Duration` in Kotlin. Read epoch-millis fields as Unix milliseconds.

## Exception boundary

| Lane | Cancellation | Other uncaught Kotlin exceptions |
| --- | --- | --- |
| ObjC | `CancellationException` → `NSError` | **fatal**. `StoreException` from `get` is fatal, not an `NSError`. |
| SKIE | → `CancellationError` | unexpected errors during flow `hasNext()` → `fatalError` |

## One failure channel

`stream` emits and never throws retrieval failures. `get` throws and never emits.
