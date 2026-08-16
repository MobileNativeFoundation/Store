# STORE-38: `building-a-store6-data-layer` Skill Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the second skill in the `store` plugin — `building-a-store6-data-layer` — for greenfield/rearchitect adopters who have no Store 4/5 code, built RED-first with a recorded baseline eval, and delivered as one PR to `matt-ramotar/Store6`.

**Architecture:** One skill, not one per platform. `SKILL.md` carries key modeling, freshness selection, persistence wiring, lifecycle, and module placement; Android/KMP/iOS enter as trigger keywords and as four reference files (`room.md`, `sqldelight.md`, `compose.md`, `swift.md`). The eval mirrors `migrating-to-store6`: a fixture repo, a pressure prompt, a no-skill baseline run recorded verbatim, a with-skill run against pass criteria, and a refactor pass that closes observed gaps.

**Tech Stack:** Claude Code plugin skills (markdown), subagent evals, Store 6 sources in `matt-ramotar/Store6` as ground truth (modules: `store6-core`, `store6-room`, `store6-sqldelight`, `store6-compose`, `store6-swift-dumps`, `store6-quickstart`, `store6-testing`).

---

## Context (read before Task 0)

- **Ticket:** [STORE-38](https://linear.app/wanderinginc/issue/STORE-38/store-plugin-building-a-store6-data-layer-skill) — "Store plugin — building-a-store6-data-layer skill". Greenfield counterpart to `migrating-to-store6`: same root failure (Store 6 is pre-alpha and absent from training data, so agents invent API spellings) but no legacy code, so the migration skill never triggers. Done when: baseline and with-skill runs recorded in `plugins/store/evals/`, skill and references shipped under `plugins/store/skills/`, every API claim stamped against a named commit, plugin version bumped.
- **Template:** [PR #46](https://github.com/matt-ramotar/Store6/pull/46) (merged as `6790606d`) shipped the plugin and the first skill. Mirror its structure, tone, and eval record exactly. Read these before writing anything:
  - `plugins/store/skills/migrating-to-store6/SKILL.md` (63 lines: frontmatter → Overview → Ground truth → Workflow → Common mistakes → Red flags → References → stamp)
  - `plugins/store/evals/migrating-to-store6/scenario.md`, `results.md`, `fixtures/UserRepository.kt`
  - `plugins/store/README.md`, `plugins/store/.claude-plugin/plugin.json`, `/.claude-plugin/marketplace.json`
- **Method:** superpowers:writing-skills. Iron Law: **no skill without a failing test first.** The RED baseline runs before one line of SKILL.md exists. If the baseline passes without the skill, stop — the skill is not needed; report that on STORE-38 instead of shipping (same honest-exit clause STORE-43 carries).
- **Ground truth discipline:** every API spelling in the skill is verified against Store 6 **source** in this repo at the checkout commit, and SKILL.md ends with `Last verified against Store `main` @ `<sha>` (pre-`6.0.0-alpha01`)`. The docs pages listed per task are outline seeds. Where a docs page and source disagree, source wins. (The docs live in `~/src/matt-ramotar/store-docs`, which currently has uncommitted working-tree edits from a site restyle — one more reason source is the authority.)
- **Scope guards:** do not build the runnable `claude plugin eval` format (that is STORE-41), do not add a second migration fixture (STORE-44), do not touch `migrating-to-store6` content (any edit to an existing skill requires its own failing test first — routing between the two skills lives in the *new* skill's description and overview instead), and do not write mutations content (STORE-39, blocked on STORE-12).
- **Repos and gates:** all work in `matt-ramotar/Store6` on a branch → PR via `gh pr create --repo matt-ramotar/Store6`. Matt merges; opening the PR is a human gate. Linear: move STORE-38 to In Progress at start; comment with results when the PR opens. If the Linear MCP (`user-Linear`) is not available in the executing session, record the update as a note in the PR description instead.

## File structure (end state)

```
plugins/store/
  README.md                                      # MODIFY: add skill row
  .claude-plugin/plugin.json                     # MODIFY: version 0.1.0 → 0.2.0, description
  skills/
    migrating-to-store6/…                        # UNTOUCHED
    building-a-store6-data-layer/
      SKILL.md                                   # CREATE: core skill (target ≤ ~90 lines)
      references/
        room.md                                  # CREATE: store6-room adapter wiring
        sqldelight.md                            # CREATE: store6-sqldelight adapter wiring
        compose.md                               # CREATE: store6-compose consumption
        swift.md                                 # CREATE: Swift/SKIE consumption
  evals/
    migrating-to-store6/…                        # UNTOUCHED
    building-a-store6-data-layer/
      scenario.md                                # CREATE: pressures + pass criteria
      results.md                                 # CREATE: baseline, with-skill, refactor pass
      fixtures/
        REQUIREMENTS.md                          # CREATE: product requirements sheet
        AtlasApi.kt                              # CREATE: existing network client
        Db.kt                                    # CREATE: existing Room 3 database (v1, no sidecars)
        ProfileViewModel.kt                      # CREATE: existing no-cache consumption
docs/superpowers/plans/2026-08-15-store38-…md    # THIS FILE: commit with the PR
```

`.claude-plugin/marketplace.json` at the repo root registers the *plugin*, not individual skills — verify it needs no change (Task 6).

---

## Task 0: Preflight — worktree, branch, stamp commit

**Files:** none (setup only)

- [ ] **Step 0.1:** Fetch and create a worktree on a fresh branch off `origin/main`:

```bash
git -C ~/src/matt-ramotar/Store6 fetch origin main
git -C ~/src/matt-ramotar/Store6 worktree add /tmp/store38-skill -b plugins/store-data-layer-skill origin/main
cd /tmp/store38-skill
```

Branch name follows the repo's plugin precedent (`plugins/store-migration-skill`). If your session imposes its own branch-name template, use that instead and note it in the PR body.

- [ ] **Step 0.2:** Record the stamp commit and confirm the plugin tree is present:

```bash
git rev-parse --short=8 HEAD    # expect 6790606d or newer; this sha goes in the SKILL.md stamp
ls plugins/store/skills/migrating-to-store6/SKILL.md .claude-plugin/marketplace.json
```

- [ ] **Step 0.3:** Copy this plan into the worktree (it ships with the PR, matching the committed plans in `docs/superpowers/plans/`):

```bash
mkdir -p docs/superpowers/plans
cp ~/src/matt-ramotar/Store6/docs/superpowers/plans/2026-08-15-store38-building-a-store6-data-layer-skill.md docs/superpowers/plans/
```

- [ ] **Step 0.4:** Move STORE-38 to In Progress in Linear (skip with a note if the MCP is unavailable).

---

## Task 1: RED — fixture, pressure prompt, baseline run

The baseline must run against a sandbox **outside** any Store checkout, with no skill present. Do not write any skill content before this task is complete.

**Files:**
- Create (sandbox): `/tmp/store38-eval/baseline/{REQUIREMENTS.md,shared/src/commonMain/kotlin/com/atlas/api/AtlasApi.kt,shared/src/commonMain/kotlin/com/atlas/db/Db.kt,app/src/main/java/com/atlas/profile/ProfileViewModel.kt}`
- Create (scratch): `/tmp/store38-eval/baseline-notes.md` (verbatim failure log; feeds `results.md` in Task 5)

- [ ] **Step 1.1: Write the fixture.** Four files. Room import spellings must be copied from this repo's `store6-room` module sources/sample — **do not guess Room 3 imports**; everything else below is complete.

`REQUIREMENTS.md`:

```markdown
# Atlas — user/session data layer requirements

Kotlin Multiplatform app: `shared/` (KMP), `app/` (Android, Compose), `iosApp/` (Swift via SKIE).
Build a shared data layer for user profiles and the auth session. `store6-core` and `store6-room`
are on the classpath; packages live under `org.mobilenativefoundation.store6.*`.

1. A profile, once loaded, is visible offline on next launch (persisted in the existing Room db).
2. Pull-to-refresh on the profile screen must hit the server.
3. The session is trusted for at most 5 minutes; after that, reads must revalidate.
4. Fetch failures retry 3 times with backoff.
5. Cap the cache at 50 users.
6. Sign-out removes all locally persisted user data immediately.
7. A push notification marks one user's profile stale without deleting it.
8. iOS consumes the same shared store from Swift.
```

`AtlasApi.kt`:

```kotlin
package com.atlas.api

class UserDto(val id: String, val name: String, val email: String)
class SessionDto(val token: String, val userId: String, val expiresAtEpochMillis: Long)

class AtlasApi {
    suspend fun getUser(id: String): UserDto = TODO("network call")
    suspend fun getSession(): SessionDto = TODO("network call")
}
```

`Db.kt` (Room 3 — copy exact import spellings from `store6-room`; database is v1 with **no** Store6 sidecar tables, so adding them is part of the task):

```kotlin
package com.atlas.db

// Room 3 (androidx.room3) imports: copy exact spellings from the store6-room sample.

@Entity(tableName = "users")
class UserEntity(@PrimaryKey val id: String, val name: String, val email: String)

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = ?") fun user(id: String): Flow<UserEntity?>
    @Upsert suspend fun upsert(row: UserEntity)
    @Query("DELETE FROM users WHERE id = ?") suspend fun delete(id: String)
    @Query("DELETE FROM users") suspend fun deleteAll()
}

@Database(entities = [UserEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
}
```

`ProfileViewModel.kt`:

```kotlin
package com.atlas.profile

// androidx.lifecycle and kotlinx.coroutines imports elided.

sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    class Loaded(val user: com.atlas.api.UserDto) : ProfileUiState
    class Failed(val error: Throwable) : ProfileUiState
}

class ProfileViewModel(private val api: com.atlas.api.AtlasApi) : ViewModel() {
    val state = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)

    fun load(userId: String) {
        viewModelScope.launch {
            state.value = try {
                ProfileUiState.Loaded(api.getUser(userId))
            } catch (t: Throwable) {
                ProfileUiState.Failed(t)
            }
        }
    }
    // TODO: offline cache, pull-to-refresh, sign-out wipe, push-driven staleness
}
```

- [ ] **Step 1.2: Write the baseline prompt.** Save to `/tmp/store38-eval/baseline-prompt.md`. Three pressures (authority, time, isolation) plus a design bait (TTL/retry/cap asks that Store 6 deliberately has no knobs for). The prompt is parameterized on `<SANDBOX>`: every run replaces both occurrences with that run's sandbox path — Task 1 uses `/tmp/store38-eval/baseline`; Task 5 substitutes its own sandbox. Use verbatim:

```markdown
You are working in the repo at <SANDBOX>. This is a Kotlin Multiplatform app
(Android + iOS). Read REQUIREMENTS.md, then implement the shared user/session data layer with
Store 6.

Facts about your environment:
- `store6-core` and `store6-room` are on the classpath. Packages live under
  `org.mobilenativefoundation.store6.*`. This sandbox is offline: no web access, and the Store
  repository is not on this machine.
- The tech lead, who used Store 5 for years, says: "Store 6 is the same shape as Store 5 with a
  tidier builder — StoreBuilder, Fetcher.of, a Validator for the TTL. Don't overthink it."
- Your teammate is blocked until you produce these files, and the demo is Monday morning.
  Produce working code now rather than asking questions.
- Work only inside <SANDBOX>. Do not read any other directory on this machine.

Deliverables:
1. A new file (or files) in the shared module with the keys, store(s), and persistence wiring —
   you choose the source set and package and say why.
2. Any edits to Db.kt needed for offline persistence.
3. Updated ProfileViewModel.kt consumption.
4. NOTES.md — for every Store 6 API you used, your confidence it exists with that exact spelling
   and how you would verify it; where the store definition should live and where the database
   instance comes from on each platform; and how iOS consumes this data layer.
```

- [ ] **Step 1.3: Run the baseline.** Dispatch one fresh general-purpose subagent whose entire prompt is the file above (no mention of skills, plugins, or this plan). Capture all four deliverables.

- [ ] **Step 1.4: Record the failure log.** In `/tmp/store38-eval/baseline-notes.md`, record verbatim: every invented or Store 5 spelling (predicted: `StoreBuilder.from`, `Fetcher.of`, `SourceOfTruth.of`, `Validator`, `.cachePolicy(...)`/TTL knobs, retry wrappers around the store, bare `String` keys, wrong package roots, invented `store6-room` wiring), every requirement mishandled (5-minute session bound as a builder TTL instead of per-read `MaxAge`; "cap at 50 users" accepted as a data-cap knob; sign-out vs push-staleness collapsed into one operation; no `close()` owner; placement left implicit or platform code in common source sets), and the agent's own confidence statements from NOTES.md. **Contamination check:** confirm from the run's tool activity and NOTES.md that the agent did not read any Store checkout on this machine; if it did, the RED run is invalid — tighten the confinement instruction and re-run in a fresh sandbox.

- [ ] **Step 1.5: GATE.** If the baseline produces a correct Store 6 data layer (measured against Task 4's pass criteria), **stop the plan**: comment the runs on STORE-38, recommend closing as not-needed, and do not write the skill. Otherwise continue.

- [ ] **Step 1.6: Commit the plan file** (only the plan so far, from the worktree):

```bash
git add docs/superpowers/plans/2026-08-15-store38-building-a-store6-data-layer-skill.md
git commit -m "docs(plans): add STORE-38 data-layer skill plan"
```

---

## Task 2: Ground-truth ledger — verify before writing

Every claim below goes into the skill only after reading the named source in this worktree. Keep a scratch ledger (`/tmp/store38-eval/ledger.md`) mapping claim → source file:line, so the Task 7 accuracy pass is mechanical.

**Files:** read-only against the worktree

- [ ] **Step 2.1: Core claims** (most already verified for `migrating-to-store6` at `c67a94ed` — re-verify at your checkout):
  - Builder DSL `store<K, V> { fetcher { … }; persistence(…); bookkeeper(…) }`; fetcher block required (`IllegalArgumentException` without) → `store6-core` builder sources; runnable shape in `store6-quickstart`.
  - `StoreKey` = `namespace: StoreNamespace` + `canonicalId(): String`; canonical id is identity/dedup; namespace is the bulk-operation unit → core key sources.
  - Exactly five `Freshness` policies (`CachedOrFetch` default, `MaxAge(notOlderThan)`, `MustBeFresh`, `StaleIfError`, `LocalOnly`) → core freshness sources.
  - Exactly four `StoreResult` kinds (`Loading`, `Data(value, origin, age, isStale, refreshing)`, `Revalidated(age)`, `Error(error, servedStale)`); `stream` never throws retrieval failures; `get` returns or throws `StoreException` → core result sources.
  - `SourceOfTruth` five operations (`reader/write/delete/deleteNamespace/deleteAll`), installed via `persistence(...)`; `@ExperimentalStoreApi` to use, plus `DelicateStoreApi` to implement → `store6-core` seam package.
  - `invalidate*` marks stale and keeps; `clear*` destroys; maintenance ops suspend and can throw `StoreException`; `close()` is a plain function → core store interface.
  - Engine: per-key single-flight dedup, stale-while-revalidate, durable invalidation, `maxIdleKeys` default 128 (idle-engine bound, **not** a data-lifetime or row-count cap), zero retries/backoff, no TTL/cache-policy knob → core config + conformance tests named in the docs "Important defaults" page (e.g. `fetcherFailure_isNotRetried_zeroConfig`, `defaultFreshness_isCachedOrFetch_zeroConfig`).
  - Module placement in a KMP project: keys, models, and the `store { }` definition are common code (`commonMain` of the shared module); platform-specific inputs (the Room database instance / SQLDelight driver) are constructed per platform and injected into the shared wiring; iOS consumes the same shared store through the shared framework → verify against the `store6-quickstart` module layout and the KMP source-set structure of `store6-room`, `store6-sqldelight`, and `store6-compose` (which source sets each publishes/compiles for). If Room 3's KMP setup verifies to a different construction pattern (e.g. a common database class with platform-provided builders), the ledger finding — not this plan's wording — dictates the final phrasing of the placement claim in SKILL.md and in pass criterion 12 (source wins).
- [ ] **Step 2.2: Room claims** → `store6-room` sources and sample: `RoomSourceOfTruth(database, rowReader, rowWriter, rowDeleter, namespaceDeleter, allDeleter)`, `RoomBookkeeper`, `Store6BookkeepingEntity`, `Store6WatermarkEntity`, `Store6BookkeeperDao`, `Store6RoomSchema.createTables(connection)`; sidecar tables `store6_bookkeeping` + `store6_watermarks`; user tables untouched; one version bump + one migration; Room 3 (`androidx.room3`, Kotlin ≥ 2.3, AGP ≥ 8.10 on Android); source-set-level opt-in covers generated DAO code.
- [ ] **Step 2.3: SQLDelight claims** → `store6-sqldelight` sources: `SqlDelightSourceOfTruth(driver, transacter, readQuery, writeRow, deleteRow, deleteNamespaceRows, deleteAllRows)`, `SqlDelightBookkeeper(driver, db)`; four self-created `store6_meta*` sidecar tables; the three boundary rules (write/read round trip; one `SqlDriver` for everything; `withTransaction` is synchronous — suspension throws `IllegalStateException` and rolls back).
- [ ] **Step 2.4: Compose claims** → `store6-compose` sources: `Store.collectAsState(key, freshness)`, `Flow.collectAsStoreState(initial)`, `collectAsStateWithLifecycle`/`collectAsStoreStateWithLifecycle`, `skipEqualData()`, `storeResultMutationPolicy()`; skipping is structural on `Data` (age excluded), `Loading`/`Revalidated`/`Error` always pass; stability conf snippet `stability/store6-stability.conf` covering `org.mobilenativefoundation.store6.core.*` and `.core.seam.*`.
- [ ] **Step 2.5: Swift claims** → committed dumps in `store6-swift-dumps`: SKIE `onEnum(of:)` exhaustive case sets (`StoreResult`: data/error/loading/revalidated; `Freshness`: five; `StoreError`: six, frozen for 6.x; `FetcherResult`: four); suspend ops exported as completion-handler (ObjC) and `async throws` (SKIE); `stream` bridges to `AsyncSequence` (task-scoped iteration); `close()` synchronous; `Duration`/`Long` flatten to `int64_t` with different meanings (`Duration` tagged raw representation vs `writtenAtEpochMillis` epoch millis); ObjC lane converts `CancellationException` to `NSError`, other uncaught Kotlin exceptions fatal.
- [ ] **Step 2.6:** Cross-check outline seeds (secondary): store-docs pages `key-design.mdx`, `important-defaults.mdx`, `concepts/freshness.mdx`, `concepts/memory-and-lifecycle.mdx`, `guides/persistence.mdx`, `quickstart.mdx`, `room.mdx`, `sqldelight.mdx`, `compose.mdx`, `guides/swift.mdx`. Source wins on any disagreement; note disagreements in the ledger.

---

## Task 3: GREEN — write SKILL.md

**Files:**
- Create: `plugins/store/skills/building-a-store6-data-layer/SKILL.md`

- [ ] **Step 3.1: Frontmatter.** Name `building-a-store6-data-layer`. Description is triggers-only (no workflow summary), third person, < 500 chars. Draft (adjust against baseline observations):

```yaml
---
name: building-a-store6-data-layer
description: Use when adding Store 6 (org.mobilenativefoundation.store6) to an app or KMP module with no Store 4/5 code to migrate — designing a data layer or offline cache, modeling StoreKeys, choosing Freshness, wiring Room or SQLDelight persistence, consuming a store from Compose or Swift, or unsure whether a Store 6 API exists. For code that already uses Store 4/5, use migrating-to-store6.
---
```

- [ ] **Step 3.2: Body.** Mirror the migration skill's shape and its "never invent an API" stance. Sections, in order:
  1. **Overview** — Store 6 is pre-alpha and absent from training data; if a spelling is not in this skill, its references, or verifiable source, assume it does not exist and say so. Route: legacy Store 4/5 code present → `migrating-to-store6`.
  2. **Ground truth** — bullets from Task 2.1 (packages/publishing, builder DSL, keys, freshness, results, persistence + adapters, maintenance + lifecycle, engine behavior you do not build: dedup, SWR, durable invalidation, `maxIdleKeys`; no retries, no TTL knob).
  3. **Workflow: design decisions in order** — (1) model keys: one namespace per record type, canonical id contains everything that changes the returned bytes; (2) write the fetcher and put retry/backoff/fallback policy inside it; (3) choose persistence: default in-memory / `store6-room` / `store6-sqldelight` / custom seam validated with the `store6-testing` contract kit; (4) choose per-read `Freshness` per call site (needs-based table: offline-first read → default; bounded trust → `MaxAge`; user-forced refresh → `MustBeFresh`; flaky network tolerance → `StaleIfError`; never fetch → `LocalOnly`); (5) place the code: keys, models, and the `store { }` definition in the shared module's `commonMain`, platform-constructed inputs (database instance, driver) injected from platform source sets, one store instance shared by Android and iOS; (6) wire consumption per platform (references); (7) assign a lifecycle owner that calls `close()`; (8) wire maintenance: stale-not-wrong → `invalidate*`, wrong-to-show → `clear*`.
  4. **Common mistakes** — table built from the Task 1 baseline log (each row = an observed failure), expected rows: TTL/`Validator`/`cachePolicy` on the builder → per-read `MaxAge` + durable invalidation; retry wrapper around the store → retries in the fetcher, engine retries zero times; "cap cache at 50 users" → no row-count cap exists; `maxIdleKeys` bounds idle engines, say so honestly; bare `String` key → `StoreKey`; sign-out via `invalidateAll` → `clearAll()` (and push-staleness via `invalidate(key)`, not delete); missing opt-ins; missing `close()`.
  5. **Red flags** — spellings that mean stop and open a reference (`StoreBuilder`, `Fetcher.of`, `SourceOfTruth.of`, `Validator`, `.cachePolicy`, `.ttl`, retry/backoff arguments on the builder, a bare-`String` key, any `store6-room`/`store6-sqldelight`/compose/Swift name not in the references).
  6. **References** — the four files with one-line whens.
  7. **Stamp** — `Last verified against Store `main` @ `<sha from Task 0>` (pre-`6.0.0-alpha01`). Re-verify spellings against the release you target.`

Target ≤ ~90 lines. Address only failures the baseline actually showed plus the ground truth needed to counter them — no hypothetical content.

---

## Task 4: GREEN — references and eval scaffold

**Files:**
- Create: `plugins/store/skills/building-a-store6-data-layer/references/{room.md,sqldelight.md,compose.md,swift.md}`
- Create: `plugins/store/evals/building-a-store6-data-layer/scenario.md`
- Create: `plugins/store/evals/building-a-store6-data-layer/fixtures/` (copy the four Task 1 fixture files verbatim)

- [ ] **Step 4.1: `references/room.md`** — from Task 2.2: dependency/plugin block (Room 3 caveats: `room3 { schemaDirectory(...) }` extension name, Kotlin ≥ 2.3, AGP ≥ 8.10), the three-declaration database diff (two sidecar entities + `store6BookkeeperDao()` accessor + version bump), the migration calling `Store6RoomSchema.createTables`, full `RoomSourceOfTruth` + `RoomBookkeeper` wiring into `store { }`, the schema claim (sidecars only; user tables untouched), and the `store6-testing` contract-kit pointer for custom seams.
- [ ] **Step 4.2: `references/sqldelight.md`** — from Task 2.3: adapter construction with generated queries, the four self-created `store6_meta*` tables (no `.sq` changes), the three boundary rules verbatim-faithful (round trip, one driver, synchronous `withTransaction` — suspension throws), and the one-logical-store-per-database note.
- [ ] **Step 4.3: `references/compose.md`** — from Task 2.4: entry points, four-kind handling in UI state (never merge kinds; `Revalidated` is a lifecycle signal), skip-equal-`Data` discipline, the stability conf snippet and what it changes, and when to collect the Flow instead of a State (event-shaped `Revalidated`/`Error`).
- [ ] **Step 4.4: `references/swift.md`** — from Task 2.5: `onEnum(of:)` switches with the exact case-set table, suspend → `async throws` (SKIE) vs completion-handler (ObjC), `stream` as `AsyncSequence` with task-scoped iteration, the `Duration`/`int64_t` trap table, and the exception-boundary warning.
- [ ] **Step 4.5: `evals/building-a-store6-data-layer/scenario.md`** — mirror the migration scenario's shape: what the fixture covers, setup (copy fixture to sandbox; with-skill run also copies the skill directory and introduces it only by its description), the three pressures + design bait, then **pass criteria (with skill)**:
  1. Keys implement `StoreKey` (`namespace`, `canonicalId()`); no bare `String` keys; user and session get distinct namespaces.
  2. Builder is the `store<K, V> { fetcher { … } }` DSL with `persistence(...)`/`bookkeeper(...)`; no `StoreBuilder`, `Fetcher.of`, `SourceOfTruth.of`, `Validator`, cache-policy/TTL knob.
  3. Imports under `org.mobilenativefoundation.store6.core` (adapter path; `.core.seam` only if a custom seam is implemented).
  4. Room: sidecar entities + DAO accessor added, version bumped, migration calls `Store6RoomSchema.createTables`, wiring via `RoomSourceOfTruth`/`RoomBookkeeper`; user table schema untouched.
  5. Freshness per read site: profile screen default `CachedOrFetch`; session bound as per-read `MaxAge(5.minutes)`; pull-to-refresh `MustBeFresh` — not builder-level TTL.
  6. Retry lives inside the fetcher with an explicit note that the engine retries zero times.
  7. "Cap at 50 users" is answered honestly: no data-cap knob; `maxIdleKeys` bounds idle engines — stated, not faked.
  8. Sign-out uses `clearAll()`; push-staleness uses `invalidate(key)`; the wrong-vs-old decision test appears.
  9. Consumption handles all four `StoreResult` kinds including `Revalidated` (plain collect or `store6-compose` entry points; no invented compose API).
  10. `@OptIn(ExperimentalStoreApi::class)` where persistence/adapters are used; store has a `close()` owner or an explicit ownership note.
  11. No invented API, no invented dependency coordinates; NOTES.md sources spellings to the skill.
  12. Placement: keys and the `store { }` definition land in the shared module's common source set; the platform-constructed input (the Room database instance) is injected from platform code rather than built in common code; NOTES.md states that iOS consumes the same shared store. Implemented or explicitly stated — not left implicit.
- [ ] **Step 4.6: Copy fixtures** into `evals/building-a-store6-data-layer/fixtures/` unchanged.
- [ ] **Step 4.7: Commit:**

```bash
git add plugins/store/skills/building-a-store6-data-layer plugins/store/evals/building-a-store6-data-layer
git commit -m "feat(plugins): draft building-a-store6-data-layer skill and eval scaffold (STORE-38)"
```

---

## Task 5: REFACTOR — with-skill run, close gaps, record results

**Files:**
- Create (sandbox): `/tmp/store38-eval/with-skill/` (fixture + skill directory)
- Create: `plugins/store/evals/building-a-store6-data-layer/results.md`
- Modify: skill files, wherever the run surfaced gaps

- [ ] **Step 5.1:** Build the with-skill sandbox at `/tmp/store38-eval/with-skill/`: copy the four fixture files, then copy `plugins/store/skills/building-a-store6-data-layer/` into the sandbox at `skills/building-a-store6-data-layer/`.
- [ ] **Step 5.2:** Dispatch a fresh subagent with the **same prompt** as Task 1 — with both `<SANDBOX>` occurrences set to this run's sandbox (`/tmp/store38-eval/with-skill`; re-runs use fresh sandboxes `/tmp/store38-eval/with-skill-2`, `-3`, … so the confinement line stays true each iteration) — plus only this line (skill introduced by description, as installed):

```markdown
This repo has a skill installed at skills/building-a-store6-data-layer/ — "<the frontmatter
description verbatim>". Use it if relevant.
```

- [ ] **Step 5.3:** Grade the deliverables against every Task 4.5 pass criterion. Any criterion failed, or any place the agent had to guess (check its NOTES.md): fix the skill or reference, then **re-run Step 5.2 in a fresh sandbox, rebuilt per Step 5.1 with the current skill files** (each fix must be re-copied into the new sandbox), until all criteria pass. Record each closed gap.
- [ ] **Step 5.4: Retrieval probes for the two references the fixture does not exercise.** `sqldelight.md` and `swift.md` must not ship unread. In a fresh sandbox containing only the skill directory (confine the agent with the same "work only inside this sandbox" instruction), ask a fresh subagent one task-shaped question per file — "wire these generated SQLDelight queries into a Store 6 store" and "consume a shared Store 6 store's stream and results from Swift" — and confirm each answer comes from the reference with no invented spellings. These are lightweight retrieval checks, not full evals; close any gap they surface.
- [ ] **Step 5.5:** Write `results.md` in the migration record's format: `## Baseline (no skill): fails` with verbatim quotes from Task 1; `## With skill: pass` with which criteria passed and the agent's own sourcing statements; `## Retrieval checks` one line each for the Step 5.4 probes; `## Refactor pass` listing gaps closed after runs; end with future eval variants worth adding (candidate: an iOS-first fixture exercising `swift.md`, and a SQLDelight-instead-of-Room variant — do not build them now).
- [ ] **Step 5.6:** Commit:

```bash
git add plugins/store
git commit -m "test(plugins): record data-layer skill eval runs (STORE-38)"
```

---

## Task 6: Registration — README, plugin.json, marketplace check

**Files:**
- Modify: `plugins/store/README.md` (Skills list)
- Modify: `plugins/store/.claude-plugin/plugin.json`
- Verify only: `/.claude-plugin/marketplace.json`

- [ ] **Step 6.1:** Add to README's Skills list: `- `building-a-store6-data-layer`: designing a new Store 6 data layer (keys, freshness, persistence, platform consumption) when there is no Store 4/5 code to migrate.`
- [ ] **Step 6.2:** `plugin.json`: bump `version` `0.1.0` → `0.2.0`; update `description` to cover both skills (e.g. "Skills for applications using Store (org.mobilenativefoundation.store): building a Store 6 data layer and migrating from Store 4/5."); extend `keywords` with `"data-layer"`, `"offline"`, `"android"`, `"ios"`, `"compose"`.
- [ ] **Step 6.3:** Validate and confirm the marketplace needs no change (it registers plugins, not skills):

```bash
jq . plugins/store/.claude-plugin/plugin.json >/dev/null && echo PLUGIN-JSON-OK
jq -r '.plugins[].source' .claude-plugin/marketplace.json   # expect ./plugins/store, unchanged
```

- [ ] **Step 6.4:** Commit:

```bash
git add plugins/store/README.md plugins/store/.claude-plugin/plugin.json
git commit -m "chore(plugins): register data-layer skill, bump store plugin to 0.2.0 (STORE-38)"
```

---

## Task 7: Three-pass review and stamp

**Files:** modify skill files only if a pass fails

- [ ] **Step 7.1: Accuracy pass.** Walk SKILL.md and all four references; every API spelling and behavioral claim must trace to a ledger entry (Task 2) pointing at source in this worktree. Anything untraceable gets verified now or deleted.
- [ ] **Step 7.2: Warrant pass.** Every "must/never/exactly" claim is evidence-backed (source or conformance test), not vibes. The stamp names the Task 0 sha.
- [ ] **Step 7.3: Reader-utility pass.** SKILL.md ≤ ~90 lines; frontmatter ≤ 1024 chars total with a triggers-only description (target < 500 chars); references are shallow tables/snippets, not essays; relative links between SKILL.md and references resolve (`ls` each target); no internal shorthand (ticket IDs, session names) inside skill content — commit messages may reference STORE-38, skill content may not.
- [ ] **Step 7.4:** Amend/commit any fixes: `git commit -am "fix(plugins): data-layer skill review pass (STORE-38)"` (skip if clean).

---

## Task 8: PR and closeout

- [ ] **Step 8.1:** Push and open the PR against `matt-ramotar/Store6` (the `--repo` flag is mandatory):

```bash
git push -u origin plugins/store-data-layer-skill
gh pr create --repo matt-ramotar/Store6 \
  --title "feat(plugins): add building-a-store6-data-layer skill" \
  --body-file /tmp/store38-pr-body.md
```

PR body follows `pull_request_template.md` and PR #46's precedent: what the skill is and why (greenfield agents invent the pre-alpha API), the RED-first test plan with both runs summarized from `results.md`, the honest limits (not compiled against a consumer build; nothing published before `6.0.0-alpha01`), checklist marked truthfully, and a Follow-ups line pointing at STORE-41/STORE-43/STORE-44 by URL.

- [ ] **Step 8.2:** Linear: comment on STORE-38 with the PR URL, the stamp sha, and one-line eval numbers (criteria passed N/N; gaps closed). Leave the issue In Progress — **merging is Matt's gate**; move to Done only after merge (or note the state if the MCP is unavailable).
- [ ] **Step 8.3:** Stop. Do not start STORE-39/40/41/43/44 in this branch or session.

---

## Failure modes for the implementer (read once)

- Writing any skill prose before the Task 1 baseline exists violates the Iron Law — delete it and start over.
- Copying `migrating-to-store6` tables into the new skill wholesale: the audiences differ; this skill teaches *design decisions*, the migration skill teaches *translation*. Cross-link, don't duplicate.
- Trusting the docs pages over source: docs are seeds; source at the stamp sha is the contract.
- Letting the fixture leak the answer (e.g. pre-adding sidecar entities or naming `RoomSourceOfTruth` in REQUIREMENTS.md): the fixture states needs, never Store 6 spellings.
- Grading the with-skill run generously: a criterion "mostly met" is a gap; fix and re-run in a fresh sandbox.
