# Results

## Baseline (no skill): fails

Run: 2026-08-15, fresh general-purpose agent, scenario as specified. Sandbox `/tmp/store38-eval/baseline`. Contamination check passed: NOTES.md states verbatim "This sandbox is offline and has no Store 6 sources," and tool activity stayed inside the sandbox.

The agent rejected the tech-lead `StoreBuilder` / `Validator` names, then invented a Store-5-shaped replacement that also does not exist. From `Stores.kt`: `Store.Builder<UserKey, UserDto>()`, `Fetcher.of(retry = RetryPolicy(maxRetries = 3, backoff = Backoff.exponential()))`, `RoomPersister(reader, writer, delete, deleteAll)`, `MemoryPolicy(maxSize = 50)`, and builder-level `.freshness(Freshness.maxAge(5.minutes))`. Keys were `data class UserKey(val id: String)` with no `StoreKey`. Reads used invented `Read.cached` / `Read.fresh` and `ReadResult`. Sign-out was `Store.clear()`. `Db.kt` gained `isStale` / `updatedAtEpochMillis` columns instead of Store 6 sidecars.

Verbatim from NOTES.md: "Spellings below are from the `org.mobilenativefoundation.store6.*` package contract and the Store 6 read/freshness/persistence model — **not** from compiling against the jars." Confidence on the invented builder was "**Medium** — Confirm whether the builder is `Store.Builder`, a top-level `store { }`, or still `StoreBuilder`." On `RoomPersister`: "**Medium-low** — the type may be `roomPersister { }`, `RoomSourceOfTruth`, or an extension on `UserDao`." On the 50-user cap: "`MemoryPolicy(maxSize = 50)` — **Medium**."

Failure pattern the skill must counter: **Store-5-shaped invention under a "tidier builder" premise**, plus treating product knobs (TTL, retry, row cap) as builder APIs, plus mutating the user table instead of adding Store 6 sidecars. Uncertainty disclosed in notes; wrong deliverable still shipped.

## With skill: pass

Run 1: 2026-08-15, fresh general-purpose agent, same scenario plus the skill directory in `/tmp/store38-eval/with-skill` (introduced only by its description). All 12 pass criteria met.

The agent's notes reject the rename premise ("Store 6 is **not** Store 5 with a tidier builder") and source spellings to the skill (stamp `6790606d`). Deliverables: `store<UserKey, UserDto> { fetcher { }; persistence(RoomSourceOfTruth(...)); bookkeeper(RoomBookkeeper(...)) }`, `UserKey` / `SessionKey` implementing `StoreKey` with distinct namespaces, Room v2 sidecars + `Store6RoomSchema.createTables` with the `users` table untouched, per-read `CachedOrFetch` / `MaxAge(5.minutes)` / `MustBeFresh`, retries inside `fetchWithRetry` with "engine retries the fetcher zero times," honest "no data-cap knob / `maxIdleKeys` bounds idle engines" for the 50-user ask, `clearAll()` vs `invalidate(key)`, four-kind `StoreResult` `when` including `Revalidated`, `@OptIn(ExperimentalStoreApi::class)`, `close()` owned by the application/DI graph, keys + `store { }` in `shared` `commonMain` with the Room instance injected. Verbatim: "If a name is not in that skill, treat it as nonexistent" (run 2; run 1: "Last verified against the installed skill").

Run 1 NOTES still guessed two spellings the skill had not stated: `StoreNamespace("users")` constructor (Medium) and `StoreResult.Error.error` as `StoreError` vs `Throwable` (Medium). Both were closed in `SKILL.md` after the run.

Run 2: 2026-08-15, fresh sandbox `/tmp/store38-eval/with-skill-2` with the post-edit skill. All 12 criteria met again. `StoreNamespace("users")` confidence rose to **High** — "skill: it is a **class**, not an enum."

## Retrieval checks

- `sqldelight.md` (sandbox `/tmp/store38-eval/probe-sqldelight`, skill directory only): agent wired `SqlDelightSourceOfTruth` + `SqlDelightBookkeeper(driver, db)` from the reference, named the four `store6_meta*` tables, and restated the three boundary rules. No invented adapter spellings.
- `swift.md` (sandbox `/tmp/store38-eval/probe-swift`, skill directory only): agent used `onEnum(of:)` case sets, SKIE `async throws` vs ObjC completion handlers, `Kotlinx_coroutines_coreFlow` + `SkieSwiftFlow` wrap, the `Duration`/`int64_t` trap, and the ObjC fatal/`NSError` boundary. No invented Swift spellings.

## Refactor pass

Gaps closed after run 1 (run 2 executed against the post-edit skill):

- `StoreNamespace` constructor and `.value` were not stated. Now in SKILL.md Keys: `StoreNamespace` is a class, `StoreNamespace("users")` exposes `.value`.
- `StoreResult.Error.error` type was not stated. Now `Error(error: StoreError, servedStale)` plus the six frozen `StoreError` kinds.

Quality-review fixes applied before run 1 shipped into the sandbox: removed a fake `org.mobilenativefoundation.store6.core.store` package label; consumer path for `store6-stability.conf`; Room 3 KMP `setDriver(BundledSQLiteDriver())` on the platform actual.

Future eval variants worth adding (do not build them now): an iOS-first fixture exercising `swift.md`, and a SQLDelight-instead-of-Room variant.
