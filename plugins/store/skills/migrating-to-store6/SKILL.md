---
name: migrating-to-store6
description: Use when migrating Kotlin code from Store 4 or Store 5 (org.mobilenativefoundation.store) to Store 6, when Store 5 spellings like StoreBuilder.from, Fetcher.of, SourceOfTruth.of, StoreReadRequest, StoreReadResponse, MutableStore, Validator, or store.fresh appear in code that should target Store 6, or when writing Store 6 code and unsure whether an API exists.
---

# Migrating to Store 6

## Overview

Store 6 is a redesigned API, not a renamed Store 5. **The Store 5 builder, request, and response vocabulary does not exist in Store 6.** A package-rename port cannot compile. Migrate by translating with the tables in [references/store5-to-store6.md](references/store5-to-store6.md), one complete screen at a time.

**Never invent a Store 6 API.** If a spelling is not in this skill, its references, or the Store 6 sources you can verify against, assume it does not exist and say so instead of guessing. "Store 6 is mostly a rename" is false regardless of who says it.

## Ground truth

- **Packages:** `org.mobilenativefoundation.store6.core` (core) and `org.mobilenativefoundation.store6.mutations` (experimental writes). Types are not directly under `org.mobilenativefoundation.store6`.
- **Publishing:** nothing is published before `6.0.0-alpha01`. Do not write dependency coordinates from memory. Verify them against the release you target. Store 5 coordinates stay published for the whole 6.x major, so both can coexist in one build.
- **Builder:** `store<K, V> { fetcher { key -> value } }`. A fetcher block is the one required input, and building without one throws `IllegalArgumentException`. `StoreBuilder.from`, `Fetcher.of`, `SourceOfTruth.of`, `Validator.by`, `.validator(...)`, `.scope(...)`, and `.cachePolicy(...)` do not exist.
- **Keys:** every key implements `StoreKey` (`namespace: StoreNamespace`, `canonicalId(): String`). A key type that does not implement `StoreKey`, such as a plain `String`, does not satisfy the `K : StoreKey` bound.
- **Reads:** freshness is per call: `stream(key, freshness)` and suspending `get(key, freshness)`, default `Freshness.CachedOrFetch`. `StoreReadRequest` does not exist. Exactly five policies: `CachedOrFetch`, `MaxAge(notOlderThan)`, `MustBeFresh`, `StaleIfError`, `LocalOnly`.
- **Results:** `StoreResult` has exactly four kinds: `Loading`, `Data(value, origin, age, isStale, refreshing)`, `Revalidated(age)`, `Error(error, servedStale)`. `StoreReadResponse`, `Initial`, `NoNewData`, `requireData()`, and `dataOrNull()` do not exist. One failure channel: `stream` emits errors and never throws retrieval failures. `get` returns a value or throws `StoreException`.
- **Persistence:** the seam `SourceOfTruth<K, V>` is installed via `persistence(...)`. Both are `@ExperimentalStoreApi`, and implementing the interface additionally requires opt-in to `DelicateStoreApi`. Prefer the `store6-room` or `store6-sqldelight` adapters. Validate custom implementations with the `store6-testing` contract kit.
- **Maintenance:** `invalidate`/`invalidateNamespace`/`invalidateAll` mark data stale and keep it. `clear`/`clearNamespace`/`clearAll` destructively remove it. Decision test: clear when the value is wrong to show, invalidate when it is merely old. Lifecycle is explicit: release a store with `close()`.
- **Engine behavior you no longer build:** per-key single-flight fetch deduplication, stale-while-revalidate, durable invalidation across restarts, and a quiescent idle-key bound (`maxIdleKeys`, default 128). Store performs no retries and no fallback chain. That policy belongs inside your fetcher.

## Workflow: one screen at a time

1. Keep the Store 5 dependency and its working screens in place. Add Store 6 alongside.
2. Design the `StoreKey`. The namespace is what you invalidate together, and `canonicalId()` includes everything that can change the returned value.
3. Port the fetcher. Move retry, backoff, and fallback policy inside it.
4. Wire persistence through an adapter or the seam.
5. Translate read sites and result handling with [references/store5-to-store6.md](references/store5-to-store6.md).
6. Translate maintenance calls (invalidate vs clear) and give the store an owner that calls `close()`.
7. Delete that screen's Store 5 store, then repeat. Until a Store 5 interop artifact ships, the two versions do not share cache state. Move whole screens, never one screen's fetch/persist/read sites split across versions.

## Common mistakes

| Mistake | Reality |
| --- | --- |
| Port by renaming imports ("it's mostly a rename") | The renamed spellings do not exist, so the port cannot compile. Translate with the tables instead. |
| `store.fresh(id)` or `impl.extensions` imports | `get(key, Freshness.MustBeFresh)` |
| Treating `StoreReadRequest.cached(key, refresh = true)` as one call | No single equivalent: collect the default `stream(key)` and call `invalidate(key)` when the caller asks to refresh |
| `when` over only Loading/Data/Error | `Revalidated` is a fourth kind: the value was confirmed fresh, there is nothing new to render |
| Passing a `String` key | Implement `StoreKey` |
| Carrying `Validator` over | Per-call `Freshness` (usually `MaxAge`) plus durable invalidation |
| Assuming no opt-ins | Persistence, seams, and all of mutations require `@OptIn(ExperimentalStoreApi::class)` |
| Translating `NoNewData` | No analog: a Store 6 fetcher has no empty-flow outcome |
| Adding TTL cache config or retry wrappers around the store | No cache policy knob exists (`maxIdleKeys` bounds idle engines, not data lifetime). Retries live inside the fetcher |

## Red flags: stop and open the tables

About to type `StoreBuilder`, `Fetcher.of`, `SourceOfTruth.of`, `StoreReadRequest`, `StoreReadResponse`, `Validator`, `store.fresh(`, or a bare-`String` key against Store 6? Those are Store 5 spellings. Open [references/store5-to-store6.md](references/store5-to-store6.md) before writing the line.

## References

- [references/store5-to-store6.md](references/store5-to-store6.md): full translation tables and a worked before/after port
- [references/component-map.md](references/component-map.md): all eight Store 5 components, row by row
- [references/from-store4.md](references/from-store4.md): the Store 4 starting point and which rows to skip
- [references/mutations.md](references/mutations.md): `MutableStore`/`Updater` to the journalled mutation path (experimental)

---

Last verified against Store `main` @ `c67a94ed` (pre-`6.0.0-alpha01`). Re-verify spellings against the release you target.
