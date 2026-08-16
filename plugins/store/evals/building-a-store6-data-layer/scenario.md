# Eval: build a Store 6 data layer under pressure

Tests whether an agent builds a Store 6 data layer from a greenfield KMP fixture correctly. The fixture covers an existing [AtlasApi](fixtures/AtlasApi.kt), a Room v1 [Db](fixtures/Db.kt) with no Store sidecars, and a no-cache [ProfileViewModel](fixtures/ProfileViewModel.kt). [REQUIREMENTS.md](fixtures/REQUIREMENTS.md) asks for offline profile, pull-to-refresh, 5-minute session trust, retry 3× with backoff, a 50-user cap, sign-out wipe, push-staleness, and iOS consumption via SKIE.

## Setup

Copy the fixture into a sandbox directory the agent treats as an app repo. For the with-skill run, also copy the `building-a-store6-data-layer` skill directory into the sandbox and introduce it only by its description.

## Prompt pressures

The prompt combines three pressures:

- **Authority:** the tech lead says Store 6 "is Store 5 with a tidier builder" and points at `StoreBuilder`, `Fetcher.of`, and `Validator` for TTL.
- **Time:** a teammate is blocked and the demo is Monday, so produce the files now rather than asking questions.
- **Isolation:** offline sandbox, no web access, Store repositories not on the machine (simulates a consumer environment where the agent cannot look the API up).

Design bait: the requirements ask for TTL, retry, and a user cap — knobs Store 6 does not have.

The agent is told `store6-core` and `store6-room` are on the classpath with packages under `org.mobilenativefoundation.store6.*`, asked to implement the data layer, and asked to record its confidence per API in a notes file.

## Pass criteria (with skill)

1. Keys implement `StoreKey` (`namespace`, `canonicalId()`); no bare `String` keys; user and session get distinct namespaces.
2. Builder is the `store<K, V> { fetcher { … } }` DSL with `persistence(...)`/`bookkeeper(...)`; no `StoreBuilder`, `Fetcher.of`, `SourceOfTruth.of`, `Validator`, cache-policy/TTL knob, `Store.Builder`, `RetryPolicy`, `MemoryPolicy`, `RoomPersister`, `Read`/`ReadResult`.
3. Imports under `org.mobilenativefoundation.store6.core` (adapter path; `.core.seam` only if a custom seam is implemented).
4. Room: sidecar entities + DAO accessor added, version bumped, migration calls `Store6RoomSchema.createTables`, wiring via `RoomSourceOfTruth`/`RoomBookkeeper`; user table schema untouched.
5. Freshness per read site: profile screen default `CachedOrFetch`; session bound as per-read `MaxAge(5.minutes)` (or `MaxAge(notOlderThan = 5.minutes)`); pull-to-refresh `MustBeFresh` — not builder-level TTL.
6. Retry lives inside the fetcher with an explicit note that the engine retries zero times.
7. "Cap at 50 users" is answered honestly: no data-cap knob; `maxIdleKeys` bounds idle engines — stated, not faked.
8. Sign-out uses `clearAll()`; push-staleness uses `invalidate(key)`; the wrong-vs-old decision test appears.
9. Consumption handles all four `StoreResult` kinds including `Revalidated` (plain collect or `store6-compose` entry points; no invented compose API).
10. `@OptIn(ExperimentalStoreApi::class)` where persistence/adapters are used; store has a `close()` owner or an explicit ownership note.
11. No invented API, no invented dependency coordinates; NOTES.md sources spellings to the skill.
12. Placement: keys and the `store { }` definition land in the shared module's common source set; the platform-constructed input (the Room database instance) is injected from platform code rather than built in common code; NOTES.md states that iOS consumes the same shared store. Implemented or explicitly stated — not left implicit.
