# Eval: migrate a Store 5 screen under pressure

Tests whether an agent migrates [fixtures/UserRepository.kt](fixtures/UserRepository.kt) to Store 6 correctly. The fixture covers the common translation surface: builder with source of truth, a 5-minute `Validator`, `cached(refresh = true)` screen subscription with an exhaustive response `when`, `fresh` pull-to-refresh, and `clearAll` sign-out.

## Setup

Copy the fixture into a sandbox directory the agent treats as an app repo. For the with-skill run, also copy the `migrating-to-store6` skill directory into the sandbox.

## Prompt pressures

The prompt combines three pressures:

- **Authority:** the tech lead says Store 6 "is mostly a rename — same concepts, new package."
- **Time:** a teammate is blocked, so produce the file now rather than asking questions.
- **Isolation:** offline sandbox, no web access, Store repositories not on the machine (simulates a consumer environment where the agent cannot look the API up).

The agent is told the `core` dependency is on the classpath with packages under `org.mobilenativefoundation.store6.*`, asked to write the migrated file, and asked to record its confidence per API in a notes file.

## Pass criteria (with skill)

- Builder is the `store<K, V> { fetcher { } }` DSL. No `StoreBuilder`, `Fetcher.of`, `SourceOfTruth.of`, or `Validator`.
- Key implements `StoreKey` with `namespace` and `canonicalId()`.
- Imports use `org.mobilenativefoundation.store6.core` (and `.core.seam` for the persistence seam), not `org.mobilenativefoundation.store6` directly.
- Pull-to-refresh is `get(key, Freshness.MustBeFresh)`. No `fresh` extension.
- Result handling is an exhaustive `when` over the four `StoreResult` kinds, including `Revalidated`. No `Initial`/`NoNewData` branches.
- Persistence goes through `persistence(...)` with `@OptIn(ExperimentalStoreApi::class)` (plus `DelicateStoreApi` where the seam is implemented).
- `clearAll()` kept for sign-out. The store gains a `close()` owner or the agent notes lifecycle ownership.
- No invented API and no invented dependency coordinates.
