---
name: building-a-store6-data-layer
description: Use when adding Store 6 (org.mobilenativefoundation.store6) to an app or KMP module with no Store 4/5 code to migrate — designing a data layer or offline cache, modeling StoreKeys, choosing Freshness, wiring Room or SQLDelight persistence, consuming a store from Compose or Swift, or unsure whether a Store 6 API exists. For code that already uses Store 4/5, use migrating-to-store6.
---

# Building a Store 6 data layer

## Overview

Store 6 is pre-alpha and absent from training data. **If a spelling is not in this skill, its references, or verifiable Store 6 source, assume it does not exist and say so.** "Store 6 is Store 5 with a tidier builder" is false regardless of who says it.

Route: legacy Store 4/5 code present → `migrating-to-store6`.

## Ground truth

- **Packages:** `org.mobilenativefoundation.store6.core` (core), `.core.seam` (expert seams), `.room`, `.sqldelight`, `.compose`. Types are not directly under `org.mobilenativefoundation.store6`.
- **Publishing:** nothing is published before `6.0.0-alpha01`. Do not write dependency coordinates from memory.
- **Builder:** `store<K, V> { fetcher { key -> value } }`. A fetcher block is required; building without one throws `IllegalArgumentException`. Optional: `persistence(...)`, `bookkeeper(...)`. `Store.Builder`, `StoreBuilder.from`, `Fetcher.of`, `SourceOfTruth.of`, `Validator`, `.cachePolicy(...)`, `.memory(...)`, `.freshness(...)` on the builder, `RetryPolicy`, `MemoryPolicy`, `RoomPersister`, and `Read`/`ReadResult` do not exist.
- **Keys:** every key implements `StoreKey` (`namespace: StoreNamespace`, `canonicalId(): String`). `StoreNamespace` is a class: `StoreNamespace("users")` exposes `.value`. Canonical id is identity/dedup. Namespace is the bulk-operation unit. A bare `String` or a type that does not implement `StoreKey` does not satisfy `K : StoreKey`.
- **Reads:** freshness is per call: `stream(key, freshness)` and `get(key, freshness)`, default `Freshness.CachedOrFetch`. Exactly five policies: `CachedOrFetch`, `MaxAge(notOlderThan)`, `MustBeFresh`, `StaleIfError`, `LocalOnly`.
- **Results:** `StoreResult` has exactly four kinds: `Loading`, `Data(value, origin, age, isStale, refreshing)`, `Revalidated(age)`, `Error(error: StoreError, servedStale)`. `StoreError` is six frozen kinds (`Fetch`, `Persistence`, `Conversion`, `FreshnessUnsatisfiable`, `Conflict`, `Missing`). `stream` emits errors and never throws retrieval failures. `get` returns a value or throws `StoreException`.
- **Persistence:** seam `SourceOfTruth<K, V>` via `persistence(...)`. Both `@ExperimentalStoreApi`; implementing the interface also needs `DelicateStoreApi`. Prefer `store6-room` or `store6-sqldelight`. Validate custom seams with `store6-testing`.
- **Maintenance:** `invalidate*` marks stale and keeps. `clear*` destroys. Decision test: clear when the value is wrong to show, invalidate when it is merely old. Maintenance ops suspend and can throw `StoreException`. `close()` is a plain function.
- **Engine behavior you do not build:** per-key single-flight dedup, stale-while-revalidate, durable invalidation, `maxIdleKeys` default 128 (idle-engine bound, **not** a data-lifetime or row-count cap). The engine retries the fetcher zero times and has no TTL/cache-policy knob. Retry/backoff/fallback belong inside the fetcher.

## Workflow: design decisions in order

1. **Model keys.** One namespace per record type. `canonicalId()` contains everything that changes the returned bytes.
2. **Write the fetcher.** Put retry, backoff, and fallback policy inside it. The engine will not retry.
3. **Choose persistence.** Default in-memory; or `store6-room` / `store6-sqldelight`; or a custom seam validated with the `store6-testing` contract kit.
4. **Choose per-read `Freshness` at each call site.** Offline-first → default `CachedOrFetch`. Bounded trust → `MaxAge`. User-forced refresh → `MustBeFresh`. Flaky-network tolerance → `StaleIfError`. Never fetch → `LocalOnly`.
5. **Place the code.** Keys, models, and the `store { }` definition live in the shared module's `commonMain`. Platform-constructed inputs (Room database instance, SQLDelight driver) are injected from platform source sets. One store instance is shared by Android and iOS. Room 3 KMP: common `@Database` + `@ConstructedBy` + platform `Room.databaseBuilder` actuals.
6. **Wire consumption** per platform — [references/compose.md](references/compose.md), [references/swift.md](references/swift.md).
7. **Assign a lifecycle owner** that calls `close()`.
8. **Wire maintenance.** Stale-not-wrong → `invalidate*`. Wrong-to-show → `clear*`. Sign-out → `clearAll()`. Push-driven staleness → `invalidate(key)`.

## Common mistakes

| Mistake | Reality |
| --- | --- |
| `Store.Builder` / `StoreBuilder.from` / `Fetcher.of` / builder `.freshness` / `Validator` / `.cachePolicy` | `store { fetcher { } }` (the DSL receiver is `StoreBuilder`, not a Store 5 factory). TTL is per-read `MaxAge` plus durable invalidation. |
| `RetryPolicy` / retry wrapper around the store | Retries live in the fetcher body. The engine retries zero times. |
| `MemoryPolicy(maxSize = 50)` or any row-count cap | No data-cap knob exists. `maxIdleKeys` (default 128) bounds idle engines, not rows. Say so honestly. |
| Bare `String` / data class key without `StoreKey` | Implement `StoreKey` (`namespace` + `canonicalId()`). Distinct record types get distinct namespaces. |
| Sign-out via `invalidateAll` / `Store.clear()` | `clearAll()`. Push-staleness is `invalidate(key)`, not a user-table `isStale` column. |
| `RoomPersister` / mutating user columns for Store metadata | `RoomSourceOfTruth` + `RoomBookkeeper`. Add sidecar entities; leave user tables untouched. |
| `Read` / `ReadResult` / three-kind `when` | `stream(key, freshness)` / `get(key, freshness)` and four `StoreResult` kinds including `Revalidated`. |
| Missing opt-in or `close()` | Persistence/adapters need `@OptIn(ExperimentalStoreApi::class)`. Someone must call `close()`. |

## Red flags: stop and open a reference

About to type `StoreBuilder.from`, `Store.Builder`, `Fetcher.of`, `SourceOfTruth.of`, `Validator`, `.cachePolicy`, `.ttl`, `.freshness(` on the builder, `RetryPolicy`, `MemoryPolicy`, `RoomPersister`, `Read.`/`ReadResult`, a retry/backoff argument on the builder, a bare-`String` key, or any `store6-room` / `store6-sqldelight` / compose / Swift name not in the references? Stop. Open the matching reference before writing the line.

## References

- [references/room.md](references/room.md): Room 3 sidecar schema, migration, `RoomSourceOfTruth` / `RoomBookkeeper`
- [references/sqldelight.md](references/sqldelight.md): generated-query wiring, `store6_meta*` sidecars, three boundary rules
- [references/compose.md](references/compose.md): `collectAsState` / lifecycle variants, four-kind UI, skip-equal-`Data`
- [references/swift.md](references/swift.md): SKIE `onEnum` case sets, `async throws` vs ObjC, `Duration`/`int64_t` trap

---

Last verified against Store `main` @ `6790606d` (pre-`6.0.0-alpha01`). Re-verify spellings against the release you target.
