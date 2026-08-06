# Store6 alpha01 Source Documentation Cleanup — Inventory

This inventory is the shared baseline and per-task tracking file for the `docs/alpha01-source-doc-cleanup` branch. Tasks 2-7 update the tables below in place as they classify and resolve hits; Task 8 appends the completion report.

## Detection sweep (verbatim)

Run from the repo root:

```bash
grep -rEn 'TD-[0-9]+|RISK-[0-9]+|STORE-[0-9]+|FS-[0-9]+|RD-[0-9]+|\(D[0-9]+[a-z]?\)|[Ii]ssue [0-9]{2,3}|PROVISIONAL|pending [Ii]ssue|PR #[0-9]+|linear\.app|\bMatt\b|signs? off|sign-off' \
  --include='*.kt' --exclude-dir=build store6-*/src
```

A second, classify-only pattern (higher false-positive rate — every hit gets classified in the inventory, FPs are allowed to remain): `ruling|ruled|adopted shape|erratum`.

Baseline capture command (this file's row set = Detection sweep ∪ classify-only pattern, sorted and deduplicated):

```bash
grep -rEn 'TD-[0-9]+|RISK-[0-9]+|STORE-[0-9]+|FS-[0-9]+|RD-[0-9]+|\(D[0-9]+[a-z]?\)|[Ii]ssue [0-9]{2,3}|PROVISIONAL|pending [Ii]ssue|PR #[0-9]+|linear\.app|\bMatt\b|signs? off|sign-off' \
  --include='*.kt' --exclude-dir=build store6-*/src | sort > detection-sweep.txt
grep -rEn 'ruling|ruled|adopted shape|erratum' \
  --include='*.kt' --exclude-dir=build store6-*/src | sort > classify-only-sweep.txt
cat detection-sweep.txt classify-only-sweep.txt | sort -u > sweep-baseline.txt
wc -l sweep-baseline.txt
```

## Measured baseline counts (2026-08-06)

- Detection sweep (ID classes + governance/speculative + personal-name/sign-off, combined single regex): **235 line hits**.
- Classify-only pattern (`ruling|ruled|adopted shape|erratum`): **30 line hits**.
- Overlap between the two patterns (same file:line matched by both): **3 line hits**.
- Combined, sorted, deduplicated baseline (235 + 30 − 3): **262 line hits**.
- Raw baseline file: `.superpowers/sdd/sweep-baseline.txt` (262 lines, this repo's worktree; not committed — regenerate with the commands above if needed).

Per-module breakdown of the 262 combined hits:

| module | hits |
| --- | --- |
| `store6-mutations` | 157 |
| `store6-core` | 48 |
| `store6-room` | 23 |
| `store6-testing` | 16 |
| `store6-benchmarks` | 5 |
| `store6-compose` | 4 |
| `store6-sqldelight` | 4 |
| `store6-compose-demo` | 2 |
| `store6-devtools` | 2 |
| `store6-mutations-testing` | 1 |
| **total** | **262** |


Note on the plan's stated expectation: the plan text (Global Constraints and Task 1 Step 2) was written from an earlier measurement ("65+132+32" / "130-200 lines") and explicitly says "re-measure, do not assume." The counts above are the actual 2026-08-06 re-measurement using the verbatim commands; they supersede the plan's placeholder numbers.

---

## Task 2 — Delete the freeze-candidate stamp (cross-module, one pattern)

Scope per the plan: every file matched by `grep -rln 'signs off' --include='*.kt' --exclude-dir=build store6-*/src`. The table below is the broader baseline subset matching Task 2's own sweep-clean check pattern (`\bMatt\b|signs? off|sign-off|issue 007`) — **44 hits** across 9 modules, wider than the plan's originally-guessed module list. Each row here is also present in its owning module's full table in the Task 3/5/6 sections below (intentional overlap — Task 2 resolves this specific stamp pattern first, cross-module; the owning module's task then re-sweeps for what remains).

| file:line | excerpt | class (P1-P4/FP/Unverifiable) | action taken |
| --- | --- | --- | --- |
| `store6-benchmarks/src/main/kotlin/org/mobilenativefoundation/store6/benchmarks/SubscriptionChurnBenchmark.kt:21` | * collections — issue 007's OQ-5 explicitly deferred grace tuning (and retry-backoff shape) to | P3 | Deleted future-work clause ("issue 007's OQ-5 ... to first 016 data; this benchmark is that data's source"); kept the READER_PIPELINE_GRACE_MILLIS contract (verified: `store6-core/.../StoreLifecycle.kt:15`) and the raw-churn sentence. |
| `store6-compose/src/commonMain/kotlin/org/mobilenativefoundation/store6/compose/CollectAsState.kt:26` | * Closed-store behavior (finalized by issue 007): calling this on a closed store fails the | P2 | Deleted "(finalized by issue 007)" landing-state parenthetical (worked-example pattern from resolution); "Closed-store behavior:" contract sentence otherwise unchanged. |
| `store6-compose/src/commonTest/kotlin/org/mobilenativefoundation/store6/compose/ClosedStoreBehaviorTest.kt:20` | * exact `Store.stream` seam they call. Close semantics were finalized by issue 007; the close | P2 | Deleted "Close semantics were finalized by issue 007; " clause; kept the OQ-3/ABI message-text contract. |
| `store6-compose-demo/src/main/kotlin/org/mobilenativefoundation/store6/composedemo/Main.kt:12` | // Process-scoped store on the landed bounded-registry engine (issue 007): idle key engines | P1 | Deleted bare "(issue 007)" tag mid-sentence; bounded-registry/LRU contract unchanged. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/internal/InMemorySourceOfTruth.kt:20` | * Canonical-key cells are intentionally unbounded until issue 007 adds their lifecycle policy. | P3 | Deleted "until issue 007 adds their lifecycle policy"; kept verified "intentionally unbounded" contract (no eviction/bound in the class body). |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/internal/StoreResultFlows.kt:23` | * FS-1's O(1)-per-collector bound and closes the lifecycle-signal bound deferred to issue 007. | P2 | Deleted "deferred to issue 007"; kept the FS-1/lifecycle-signal-bound contract. The `FS-1` tag on this line is untouched, out of Task 2's pattern scope — left for Task 3's own sweep. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/Bookkeeper.kt:33` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 | Deleted stamp sentence verbatim (worked example) + orphaned blank KDoc line; rest of KDoc unchanged. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/Bookkeeper.kt:105` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 | Deleted stamp sentence verbatim (worked example) + orphaned blank KDoc line; rest of KDoc unchanged. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/Fetcher.kt:15` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 | Deleted stamp sentence + preceding blank line, keeping the single blank separator before `@param` intact. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/FreshnessValidator.kt:15` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 | Deleted stamp sentence verbatim (worked example) + orphaned blank KDoc line (`FreshnessContext` doc). |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/FreshnessValidator.kt:30` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 | Deleted stamp sentence verbatim + orphaned blank KDoc line (`FreshnessValidator` interface doc). |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/FreshnessValidator.kt:42` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 | Deleted stamp sentence verbatim + orphaned blank KDoc line (`FetchPlan` sealed interface doc). |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/FreshnessValidator.kt:50` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 | Deleted stamp sentence verbatim + orphaned blank KDoc line (`FetchPlan.Skip` doc). |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/FreshnessValidator.kt:57` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 | Deleted stamp sentence verbatim + orphaned blank KDoc line (`FetchPlan.Fetch` doc). |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/FreshnessValidator.kt:66` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 | Deleted stamp sentence verbatim + orphaned blank KDoc line (`FetchPlan.Conditional` doc). |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/KeyEvents.kt:26` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may | P2 | Deleted 2-line wrapped stamp + orphaned blank KDoc line; rest of KDoc unchanged. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/Overlay.kt:39` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes | P2 | Deleted 2-line wrapped stamp + orphaned blank KDoc line; rest of KDoc unchanged. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/StoreResults.kt:15` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 | Deleted stamp sentence verbatim + orphaned blank KDoc line; rest of KDoc unchanged. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/StoreRuntime.kt:13` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may | P2 | Deleted 2-line wrapped stamp + preceding blank line, keeping the blank separator before `@param` intact. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/StoreTelemetry.kt:25` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may | P2 | Deleted 2-line wrapped stamp + orphaned blank KDoc line; rest of KDoc unchanged. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/StoreWriteHandle.kt:12` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may | P2 | Deleted 2-line wrapped stamp (the brief's worked example) + preceding blank line, keeping the blank separator before `@param` intact. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/TransactionalSourceOfTruth.kt:24` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 | Deleted stamp sentence + preceding blank line, keeping the blank separator before `@param` intact. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/WallClock.kt:13` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 | Deleted stamp sentence verbatim + orphaned blank KDoc line; rest of KDoc unchanged. |
| `store6-devtools/src/commonMain/kotlin/org/mobilenativefoundation/store6/devtools/StoreDevtoolsEvent.kt:13` | * decided: values never cross this seam. The seam remains a freeze candidate and sign-off is held. | P2 | Deleted "The seam remains a freeze candidate and sign-off is held." sentence; kept the v0-vocabulary/wire-format sentence. |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationSourceOfTruth.kt:41` | * Cells are intentionally unbounded, matching the core default's posture until the issue 007 | P3 | Deleted "until the issue 007 lifecycle policy applies here"; kept "intentionally unbounded, matching the core default's posture" contract. |
| `store6-room/src/commonMain/kotlin/org/mobilenativefoundation/store6/room/RoomBookkeeper.kt:41` | * This seam remains FREEZE CANDIDATE pending Matt signature. | P2 | Deleted stamp variant sentence + orphaned blank KDoc line; rest of KDoc unchanged. |
| `store6-room/src/commonMain/kotlin/org/mobilenativefoundation/store6/room/RoomSourceOfTruth.kt:212` | * Freeze candidate: issue 007 has landed; the seam freezes only after Matt signs the prepared | P2 | Deleted 2-line stamp variant ("Freeze candidate: issue 007 has landed ... sign-off package.") + orphaned blank KDoc line; rest of KDoc unchanged. |
| `store6-room/src/commonMain/kotlin/org/mobilenativefoundation/store6/room/RoomSourceOfTruth.kt:213` | * sign-off package. | P2 | Same edit as :212 (continuation line of the same stamp sentence). |
| `store6-room/src/commonMain/kotlin/org/mobilenativefoundation/store6/room/Store6BookkeepingEntity.kt:14` | * This surface is a seam freeze candidate pending Matt's signature. | P2 | Deleted stamp variant sentence + orphaned blank KDoc line; rest of KDoc unchanged. |
| `store6-sqldelight/src/commonMain/kotlin/org/mobilenativefoundation/store6/sqldelight/SqlDelightBookkeeper.kt:35` | * This seam remains FREEZE CANDIDATE awaiting Matt signature. | P2 | Deleted stamp variant sentence + orphaned blank KDoc line; rest of KDoc unchanged. |
| `store6-sqldelight/src/commonMain/kotlin/org/mobilenativefoundation/store6/sqldelight/SqlDelightSourceOfTruth.kt:62` | * Seam status: FREEZE CANDIDATE awaiting Matt signature; never frozen. | P2 | Deleted stamp variant sentence + preceding blank line, keeping the blank separator before `@param` intact. |
| `store6-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/FakeStore.kt:40` | * Invalidation implements Decision #37 (Matt, 2026-07-20): it is a stale-mark only and never | P2 | Deleted "Decision #37 (Matt, 2026-07-20): " provenance prefix; kept "invalidation is a stale-mark only and never consumes a script" contract. |
| `store6-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/FakeStore.kt:59` | * The seam consumed here is a FREEZE CANDIDATE, not frozen: freeze sign-off remains held until | P2 | Deleted stamp sentence ("The seam consumed here is a FREEZE CANDIDATE ... Matt signs off."); kept the following close()-behavior sentences (same edit as :60/:63). |
| `store6-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/FakeStore.kt:60` | * issue 007 lands and Matt signs off. [close] is synchronous and idempotent. Active collectors are | P2 | Same edit as :59 (continuation of the stamp sentence; "[close] is synchronous..." onward kept). |
| `store6-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/FakeStore.kt:63` | * text were finalized by issue 007 against the engine's close lifecycle. | P2 | Rewrote "text were finalized by issue 007 against the engine's close lifecycle" to "text are pinned against the engine's close lifecycle" (same edit as :59/:60), keeping the contract that these details are pinned/verified against engine behavior. |
| `store6-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/FakeStore.kt:274` | * Close semantics finalized by issue 007. | P2 | Deleted "Close semantics finalized by issue 007." line; kept "Closes this fake synchronously and idempotently." |
| `store6-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/FakeStore.kt:437` | * Decision #37 (ruled by Matt, 2026-07-20): invalidate is a stale-mark only, the engine's | P2 | Deleted "Decision #37 (ruled by Matt, 2026-07-20): " provenance prefix; kept the epoch-bump-analog contract. |
| `store6-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/FakeStore.kt:487` | // Finalized by issue 007: core keeps STORE_CLOSED_MESSAGE internal by design (FS-5 — | P2 | Deleted "Finalized by issue 007: " prefix; kept the STORE_CLOSED_MESSAGE/FS-5 rationale. The `FS-5` tag on this line is untouched, out of Task 2's pattern scope — left for Task 6's own sweep. |
| `store6-testing/src/commonTest/kotlin/org/mobilenativefoundation/store6/testing/FakeStoreConformanceTest.kt:32` | * There is no invalidate-divergence row: Decision #37 (Matt, 2026-07-20) aligned the fake to the | P2 | Deleted "Decision #37 (Matt, 2026-07-20)" provenance subject; rewrote to "the fake is aligned to..." keeping the stale-mark-only/next-demand contract. |
| `store6-testing/src/commonTest/kotlin/org/mobilenativefoundation/store6/testing/FakeStoreConformanceTest.kt:191` | // THE Decision #37 alignment pin (ruled by Matt, 2026-07-20): with NO active demand, | P2 | Deleted "THE Decision #37 alignment pin (ruled by Matt, 2026-07-20): " prefix; kept "With NO active demand, invalidate defers..." contract (rest of the comment unchanged). |
| `store6-testing/src/commonTest/kotlin/org/mobilenativefoundation/store6/testing/FakeStoreConformanceTest.kt:375` | // Finalized by issue 007: pins verified against StoreCloseLifecycleTest in store6-core. | P2 | Deleted "Finalized by issue 007: " prefix; kept "Pins verified against StoreCloseLifecycleTest in store6-core." |
| `store6-testing/src/commonTest/kotlin/org/mobilenativefoundation/store6/testing/FakeStoreConformanceTest.kt:386` | // Finalized by issue 007: pins verified against StoreCloseLifecycleTest in store6-core. | P2 | Deleted "Finalized by issue 007: " prefix (second, distinct occurrence in `close_cancelsActiveCollectors`); kept "Pins verified against StoreCloseLifecycleTest in store6-core." |
| `store6-testing/src/commonTest/kotlin/org/mobilenativefoundation/store6/testing/UserViewModelSampleTest.kt:45` | fake.enqueueFetchValue(key, User("42", "Matt")) | FP | No change. `"Matt"` is a test-fixture `User.name` string literal (protected code/data value), not documentation or a governance/provenance reference — coincidental regex match. Flagged in the task report as a deliberate deviation from the literal "gate returns zero" wording, since altering test data is out of this task's charter. |
| `store6-testing/src/commonTest/kotlin/org/mobilenativefoundation/store6/testing/UserViewModelSampleTest.kt:49` | assertEquals("Matt", assertIs<UserUiState.Ready>(awaitItem()).name) | FP | No change (same rationale as :45 — asserts against the same test-fixture value). |

---

## Task 3 — store6-core remaining internal references

Scope: all baseline hits in `store6-core/src` (full per-module list; Task 2 above resolves the `signs off` stamp subset first, then Task 3 sweeps `store6-core/src` again for what remains).


#### `store6-core` (48 hits at baseline)

| file:line | excerpt | class (P1-P4/FP/Unverifiable) | action taken |
| --- | --- | --- | --- |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/internal/InMemorySourceOfTruth.kt:20` | * Canonical-key cells are intentionally unbounded until issue 007 adds their lifecycle policy. | P3 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/internal/KeyRegistry.kt:21` | *   bookkeeper, so a recreated engine is semantically identical (issue 006's hydration | unclassified | — |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/internal/KeyRegistry.kt:27` | * - Creation still runs [verifyStableCanonicalId] once per residency (FS-6). | unclassified | — |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/internal/StoreResultFlows.kt:23` | * FS-1's O(1)-per-collector bound and closes the lifecycle-signal bound deferred to issue 007. | P2 (Task 2, partial) | Task 2 deleted "deferred to issue 007". The `FS-1` tag on this same line is still present — remains for this task's own sweep/classification. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/internal/ValueEnvelope.kt:14` | * FS-6 conservative posture: the value reports `isStale = true`, age zero, and never | unclassified | — |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/Bookkeeper.kt:105` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/Bookkeeper.kt:33` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/Fetcher.kt:15` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/FreshnessValidator.kt:15` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/FreshnessValidator.kt:30` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/FreshnessValidator.kt:42` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/FreshnessValidator.kt:50` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/FreshnessValidator.kt:57` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/FreshnessValidator.kt:66` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/KeyEvents.kt:26` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/Overlay.kt:39` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/StoreResults.kt:15` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/StoreRuntime.kt:13` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/StoreTelemetry.kt:25` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/StoreWriteHandle.kt:12` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail (brief's worked example). |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/TransactionalSourceOfTruth.kt:24` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/TransactionalSourceOfTruth.kt:8` | * Optional atomicity capability for a [SourceOfTruth] (TD-11). Detectable via | unclassified | — |
| `store6-core/src/commonMain/kotlin/org/mobilenativefoundation/store6/core/seam/WallClock.kt:13` | * Freeze candidate: this surface freezes only after issue 007 lands and Matt signs off; shapes may still change until then. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/EmissionSequenceConformanceTest.kt:242` | // T2E ruling: a cold-baseline 304 commits ObsoleteRevalidation and legally | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/EmissionSequenceConformanceTest.kt:319` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/FreshnessPolicyConformanceTest.kt:490` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/KeyEngineSourceOfTruthRaceTest.kt:648` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/PublicSurfaceTest.kt:15` | /** The FS-6 detector: an unstable canonicalId fails fast and names the fix. */ | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/ReaderGenerationRecoveryTest.kt:134` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/SourceOfTruthBindingConformanceTest.kt:2073` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/SourceOfTruthCancellationConformanceTest.kt:537` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/SourceOfTruthConformanceTest.kt:353` | // FS-6 + hydration: unknown provenance serves and triggers exactly one revalidation. | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/SourceOfTruthConformanceTest.kt:394` | // FS-1: persisted truth participates in startup before its revalidation can overwrite it. | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/SourceOfTruthConformanceTest.kt:489` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/StoreConformanceTest.kt:269` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/StoreConformanceTest.kt:35` | expectNoEvents() // live, not completed (FS-1) | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/StoreConformanceTest.kt:41` | // (b1) fetcher throws -> stream emits Error and stays live (FS-5: stream never throws) | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/StoreConformanceTest.kt:55` | // (b2) fetcher throws -> get throws StoreException carrying StoreError.Fetch (FS-2/FS-5) | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/StoreInvalidationConformanceTest.kt:318` | assertTrue(exception.message!!.contains("test/1")) // FS-5: which key | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/StoreInvalidationConformanceTest.kt:319` | assertTrue(exception.message!!.contains("clear")) // FS-5: what happened | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/StoreInvalidationConformanceTest.kt:655` | // 006 fenced-clear ruling: an already-active pipeline may queue one duplicate | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/StoreInvalidationConformanceTest.kt:825` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/StoreRevalidationConformanceTest.kt:102` | // T2E ruling: a cold-baseline 304 commits ObsoleteRevalidation and legally | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/StoreRevalidationConformanceTest.kt:224` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/StoreRevalidationConformanceTest.kt:34` | // T2E ruling: a cold-baseline 304 commits ObsoleteRevalidation and legally | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/StoreRuntimeTest.kt:156` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/internal/KeyEnginePlanningTest.kt:289` | // T2E ruling (017 post-merge): a 304 that launched against a null residence baseline but | unclassified | — |
| `store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/internal/OverlayProjectionProtocolTest.kt:1740` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |

---

## Task 4 — store6-mutations

Scope: all baseline hits in `store6-mutations/src` (full per-module list). Densest module.


#### `store6-mutations` (157 hits at baseline)

| file:line | excerpt | class (P1-P4/FP/Unverifiable) | action taken |
| --- | --- | --- | --- |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationBookkeeper.kt:21` | * inaccessible internal default is never substituted (D9). Certified against | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:1052` | * The suspending-facade resolution door (D14): one attempt, then the sanctioned | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:1071` | * The keyed-drain resolution door (D14): a failed terminal resolution parks one owned durable | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:1421` | * Alias interaction (D15a): the pass drains the durable-client-sequence prefix that existed | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:143` | // ruled 021 shape; 022 owns durable client rows. | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:1730` | // pass replays the same immutable generation (D2). Only a non-cancellation | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:231` | // R-0 §1's contiguous locally retired prefix, in-memory form, advertised on pushes (D15a). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:2741` | // Cross-namespace acknowledgement rejection retains its separately ruled posture. | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:3695` | * behind it (D12). Ties (direct journal appends in module tests use the default sequence) | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:3950` | * unrepresentable (D15a). Issue 022 lands tombstone storage and hydration; the ack/clear and | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:4023` | /** Advances the in-memory contiguous retired prefix (D15a); gaps hold the high-water. */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:4096` | /** A library-owned immutable snapshot of captured metadata fields (D2). */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:54` | /** The single in-memory attempt generation every 021 push transmits; merges are 023's (D2). */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:64` | /** Stable machine detail for a resolver that returned null during global drain (D14). */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:67` | /** Stable machine detail for a resolver whose returned pair mismatched the request (D14). */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:702` | * One idempotent keyed foreground pass (D12): captures the unprojected confirmed base through | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:704` | * retry or backoff. A ruled pre-ack codec/projection failure parks that head and continues its | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:706` | * resolves the terminal alias identity before calling this (D15a); a mid-pass activation | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:70` | /** Stable machine detail for a resolver that threw a non-cancellation failure (D14). */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:729` | * One idempotent global foreground pass (D12): enumerates durable identities from the | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:731` | * (D14), and continues past identities that fail to resolve after parking exactly one owned | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:76` | /** Stable machine detail for a keyed drain whose aliased terminal key failed to resolve (D14). */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:926` | * Snapshot rows for every durable identity in durable client-sequence order (D3): the | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:946` | * Durably parked intents only (D3). Always empty at 021: parking is 023's transition over | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEngine.kt:970` | /** The terminal identity for [identity] under the active alias edges (D15a). */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEvents.kt:13` | * Read-only, in-process advisory mutation telemetry (D4). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEvents.kt:268` | * A non-cancellation checkpoint transport, protocol, or persistence failure (D12). Client-scoped | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationEvents.kt:293` | * TD-8 note: a `MutableSharedFlow` configured with [BufferOverflow] is legal advisory plumbing; | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationInspection.kt:128` | * A truthful snapshot of one nonterminal active intent (D3). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationInspection.kt:15` | * The total public mapping of every nonterminal active execution phase (D3). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationInspection.kt:165` | * A durably parked intent (D3). Dead letters contain only parked entries; parking is legal only | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationInspection.kt:205` | * An ephemeral projection-failure report carrying the exact local `Throwable` (D3). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationInspection.kt:45` | * A normalized, restart-safe failure record (D3). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationJournal.kt:185` | * The durable identities that currently hold pending intents, in first-enqueue order (D12): | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationJournal.kt:442` | // Cache-fronted canonical alias routing (D15a). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationJournal.kt:445` | /** Stable machine detail for a canonical target in a different namespace (D15a). */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationJournal.kt:448` | /** Stable machine detail for a second canonical target claimed for an aliased source (D15a). */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationJournal.kt:451` | /** Stable machine detail for a canonical target whose chain reaches back to its source (D15a). */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationJournal.kt:454` | /** Stable machine detail for a generation retry acknowledging a different canonical target (D15a). */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationJournal.kt:459` | * The lifecycle of one alias edge (D15a): `PENDING` between validated acknowledgement receipt and | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationJournal.kt:469` | /** One normalized same-namespace full-pair redirect: source identity to target identity (D15a). */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationJournal.kt:516` | /** The outcome of validating one acknowledged canonical target at ack receipt (D15a). */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationJournal.kt:54` | * records (D8): every key is normalized to its full identity pair; ordering is namespace effects | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationJournal.kt:579` | * persist or publish, an optional pending redirect edge (D15a): | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationJournal.kt:93` | * inspection shapes are proven against the ruled vocabulary (D3, R-0 §3). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:111` | * The result of a pure registered invalidation function `(key, args) -> StaleSet<K>` (D8). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:129` | * The library-owned capture carrier handed to the optional precondition selector (D2). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:169` | * The immutable, library-built transport request for one attempt generation (D2). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:16` | * (D13). Push, acknowledgement, conflict, attempt, and adoption carriers contain non-null | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:262` | * Backend coherence obligation for confirmed deletion (D13), certified by returning | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:272` | * retention (D15b). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:285` | * The backend's acknowledgement of one pushed generation (D13). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:289` | * library alone constructs pushes, retirements, identities, inspection rows, and failures (D11). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:303` | * must return the same canonical target or the intent parks as a protocol violation (D15a). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:322` | * Backend coherence obligation (D13): Every fetch begun after an Absent acknowledgement returns | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:334` | * The library-built retirement checkpoint request (D15b). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:351` | * The consumer-built confirmation of a retirement checkpoint (D15b). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:368` | * persisted server-confirmed prefix. Returns the validated new confirmed prefix. Issue 023 | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:388` | * Library-side exact-pair resolution validation (D14): the engine calls this on every resolver | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:412` | * The explicit outcome of a consumer merge hook after a precondition conflict (D2). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:48` | * components verbatim (D14). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocol.kt:84` | * decoders remain until the corresponding rows are safely retired and pruned (D7). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationSourceOfTruth.kt:24` | * engine; core's inaccessible internal default is never substituted, and Issue 024 can select and | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationSourceOfTruth.kt:26` | * (D9). Certified against `SourceOfTruthContractKit`: | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationSourceOfTruth.kt:41` | * Cells are intentionally unbounded, matching the core default's posture until the issue 007 | P3 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:177` | * Returns the value for the terminal canonical identity of [key] (D15a); one resolution | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:179` | * (D14). This read is never projected by the overlay; overlays apply only to [stream]. | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:190` | * Marks the terminal canonical identity of [key] stale (D15a); one resolution attempt, | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:191` | * throwing a `StoreResults.conversionError`-backed [StoreException] on failure (D14). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:199` | * Destructively removes the value for the terminal canonical identity of [key] (D15a); one | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:201` | * failure (D14). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:250` | * Runs one idempotent, scheduler-agnostic global foreground pass (D12): every durable | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:252` | * [MutationKeyResolver] with exact-pair validation (D14). An identity that fails to resolve | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:270` | * Aliases are followed as durable identity pairs only (D14): this inspection never | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:283` | * identities, in durable client-sequence order (D3). Retired history never appears. | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:292` | * Returns the durably parked intents (D3). Dead letters contain only `PARKED` entries; | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:294` | * is always empty: parking is produced by Issue 023 over Issue 022's durable rows. | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:308` | * Read-only, in-process advisory lifecycle events (D4): replay `0`, extra buffer capacity | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:310` | * or settlement protocol; durable truth remains inspection. Issue 023 owns causal emission. | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:316` | /** The exact Bookkeeper the engine retained (D9); test/022/024 verification door. */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:320` | /** The exact SourceOfTruth the engine retained (D9); test/022/024 verification door. */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:335` | // (D14): the waiter observes the closed signal, cancels promptly, and its `first` | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:362` | * The ruled entry point (D1): restart behavior is compile-time required — the registry, server, | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:366` | * delegated Store AND retained by the engine, so Issue 024's transactional decorator can select | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:367` | * and report its path instead of silently discovering an inaccessible core default (D9). The | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:38` | * and routes every key-taking operation through the canonical alias table (D15a). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:40` | * PROVISIONAL pending Issue 021: this facade deliberately withholds the raw engine write handle. | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:66` | * Observes retrieval state and values for the terminal canonical identity of [key] (D15a). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStore.kt:68` | * Alias liveness contract (D14): before resolving, the stream snapshots the mutation-owned | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt:151` | * The surface and its registration validation land at Issue 021 (D2); precondition selection | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt:152` | * and merge execution are owned by Issue 023's fixed conflict pipeline (R1-19), so nothing | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt:206` | * Registers the conflict policy surface ruled by D2. | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt:208` | * Registration-time validation only at Issue 021: each policy registers at most once per block, | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt:209` | * and nothing registered here is executed before Issue 023's pipeline lands. There is | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt:229` | * Store6 selects the candidate's captured metadata. Execution is owned by Issue 023 and runs | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt:230` | * once per newly prepared semantic generation, never on a transport retry (D2). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt:248` | * repeat policy are owned by Issue 023 (D2, R1-19). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt:275` | * The validated conflict policy retained for Issue 023's pipeline. | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt:277` | * Stored, never executed, at Issue 021. | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt:28` | * builder doors (D1). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt:294` | * the delegated core Store and the mutation engine (D9). [applyCoreConfiguration] replays every | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt:34` | * silently substituted (D9). Issue 024 selects its transactional decorator — or reports its | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt:94` | * sides (D9). Custom implementations should be validated with the source-of-truth contract | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilder.kt:95` | * kit. Issue 024 selects transactional adoption — or reports its explicit non-transactional | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutatorRegistry.kt:10` | /** The fixed args-codec version used by every `delete` registration (D7). */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutatorRegistry.kt:119` | * `null` means exactly "decline this intent" (D13); a declined head remains pending and | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutatorRegistry.kt:124` | * [stales] is the pure declarative invalidation function (D8): equal inputs must produce | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutatorRegistry.kt:14` | * The one deliberate args-codec specialization (D7): Store6 owns the `delete` codec at fixed | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutatorRegistry.kt:17` | * so Issue 022 can normalize the throw. | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutatorRegistry.kt:184` | * confirmed base is ignored (D1). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutatorRegistry.kt:201` | * codec whose encoding is exactly zero bytes (D7). | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/MutatorRegistry.kt:80` | /** Projects [base] through the registered mutator; `null` means decline only (D13). */ | unclassified | — |
| `store6-mutations/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/storage/MutationJournalStorage.kt:104` | * Applies a ruled execution-state transition. | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationAckPathTest.kt:1329` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationAckPathTest.kt:891` | // DeleteAndCreatePending): under the ruled D13, delete is drainable — a projected Absent | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationAliasFacadeTest.kt:115` | // definition, not stale (ruling: pending UI keys on origin == OVERLAY). | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationAliasFacadeTest.kt:123` | // to delegate.stream(canonical) (D15a): the confirmed canonical frame arrives | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationAliasFacadeTest.kt:1337` | * (D15a). The staging mirrors the retry-mismatch arm of | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationAliasFacadeTest.kt:1513` | * canonical id (D15a); every other key acknowledges with an unchanged identity. | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationAliasFacadeTest.kt:1526` | /** The ruled public entry point, used where no engine door is needed. */ | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationAliasFacadeTest.kt:1811` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationAliasFacadeTest.kt:44` | * R1-20/R1-24/R1-09's 021 slice: the same-process canonical alias facade (D15a) and the D14 | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationAliasFacadeTest.kt:69` | // queued siblings from source and target merge by durable client sequence (D15a). | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationAliasFacadeTest.kt:793` | // source key, no completion (D14). | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationAliasFacadeTest.kt:857` | // the thrown cause in the immediate public exception only (D14). | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationAliasFacadeTest.kt:875` | // Resolver null has no cause (D14). | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationAliasFacadeTest.kt:911` | // mutate resolves BEFORE the append: failure creates no intent anywhere (D14). | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationAliasFacadeTest.kt:951` | // reconstructed, so a dead resolver cannot fail this inspection (D14). | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationDrainTest.kt:257` | // `update` over a stably absent base declines (D13): the head stays PENDING and blocks | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationDrainTest.kt:33` | * passes (D12) and the resolver — not any live key map — is global drain's correctness path | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationDrainTest.kt:34` | * (D14). Restart enumeration is 022's `MutationJournalContractTest`; parked-identity | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationEventSurfaceTest.kt:165` | // Sealed exhaustiveness over the event root needs exactly the three ruled branches. | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationEventSurfaceTest.kt:23` | // R1-22: the non-generic sealed algebra exposes the exact ruled stable fields for both the | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationInspectionTest.kt:141` | // All identities, durable client-sequence order, real enqueue stamps (D3). | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationInspectionTest.kt:205` | // Dead letters contain only durably PARKED executions (D3). 021 records the normalized | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationInspectionTest.kt:25` | * R1-14/R1-15's 021 slices: truthful pending/pendingWrites/deadLetters snapshots (D3) and the | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationInspectionTest.kt:353` | // Poison is the ephemeral exact-Throwable flow (D3); it is not a drain failure carrier, | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationInspectionTest.kt:36` | // The ruled total mapping (D3, R-0 §3): every nonterminal active phase maps to exactly | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationJournalContractTest.kt:905` | // Adoption already committed its ruled ACKED -> EFFECTS_PENDING boundary; the injected | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationKeyResolverTest.kt:23` | * R1-02's 021 slice: the required resolver is global drain's correctness path (D14). Every test | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationProtocolTest.kt:525` | // T4.1 bullet: stable public enums expose the exact ruled value sets. | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilderTest.kt:203` | // ...and the same exact instances retained for the engine (D9). | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilderTest.kt:206` | // conflicts door (D2): surface and registration validation land at 021; nothing | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilderTest.kt:348` | /** Minimal ruled two-method server: acknowledges this client's value and confirms retirement. */ | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilderTest.kt:38` | * The ruled `MutationStoreBuilder` mirrors core's optional doors, exposes no overlay door, and | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilderTest.kt:40` | * Store and the mutation engine (D9). ABI absence of an overlay setter, runtime, and write-handle | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilderTest.kt:49` | // factory parameters, never builder doors. The named-argument call pins the ruled | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationStoreBuilderTest.kt:88` | // Engine side: the mutation engine retained the caller's exact instance (D9). | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationsTestFixtures.kt:22` | * (D15a): a canonical target in another namespace must be constructible so cross-namespace | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationsTestFixtures.kt:33` | /** Exact-pair resolver for the module's single-namespace test key (D14). */ | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationsTestFixtures.kt:92` | * redirects a provisional identity (D15a); [absentPushBehavior] scripts Absent-projection pushes | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationsWalkingSkeletonTest.kt:246` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationsWiringSpikeTest.kt:21` | * Successor to `lastOverlayRegistrationWins` (T4.3's ruled compile-time posture). | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutatorSugarTest.kt:230` | // Any other durable pair is a codec violation for Issue 022 to normalize as CODEC. | unclassified | — |
| `store6-mutations/src/commonTest/kotlin/org/mobilenativefoundation/store6/mutations/MutatorSugarTest.kt:93` | // Null is the decline signal (D13): the declined head never becomes an attempt and never | unclassified | — |
| `store6-mutations/src/jvmTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationApiSurfaceTest.kt:29` | "Committed KLib dump is missing the ruled MutationJournalStorage seam.", | unclassified | — |
| `store6-mutations/src/jvmTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationApiSurfaceTest.kt:9` | * R1-13: the committed KLib declaration exposes the ruled `MutationJournalStorage` seam, but no | unclassified | — |

---

## Task 5 — Adapter modules (store6-room, store6-sqldelight, store6-compose, store6-mutations-sqldelight)

Scope: all baseline hits in each of the four adapter modules (full per-module lists; Task 2 above resolves the `signs off` stamp subset in `store6-room` and `store6-sqldelight` first).


#### `store6-room` (23 hits at baseline)

| file:line | excerpt | class (P1-P4/FP/Unverifiable) | action taken |
| --- | --- | --- | --- |
| `store6-room/src/commonMain/kotlin/org/mobilenativefoundation/store6/room/RoomBookkeeper.kt:22` | * Durable Room [Bookkeeper] backed by the adapter-owned TD-6 sidecar. | unclassified | — |
| `store6-room/src/commonMain/kotlin/org/mobilenativefoundation/store6/room/RoomBookkeeper.kt:41` | * This seam remains FREEZE CANDIDATE pending Matt signature. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-room/src/commonMain/kotlin/org/mobilenativefoundation/store6/room/RoomSourceOfTruth.kt:206` | * When [withTransaction] wraps nested writes for the future TD-11 mutations decorator, nested | unclassified | — |
| `store6-room/src/commonMain/kotlin/org/mobilenativefoundation/store6/room/RoomSourceOfTruth.kt:212` | * Freeze candidate: issue 007 has landed; the seam freezes only after Matt signs the prepared | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-room/src/commonMain/kotlin/org/mobilenativefoundation/store6/room/RoomSourceOfTruth.kt:213` | * sign-off package. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-room/src/commonMain/kotlin/org/mobilenativefoundation/store6/room/Store6BookkeeperDao.kt:8` | /** Room primitives for the adapter-owned TD-6 bookkeeping sidecar. */ | unclassified | — |
| `store6-room/src/commonMain/kotlin/org/mobilenativefoundation/store6/room/Store6BookkeepingEntity.kt:14` | * This surface is a seam freeze candidate pending Matt's signature. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-room/src/commonMain/kotlin/org/mobilenativefoundation/store6/room/Store6BookkeepingEntity.kt:8` | * Adapter-owned TD-6 bookkeeping sidecar. | unclassified | — |
| `store6-room/src/commonMain/kotlin/org/mobilenativefoundation/store6/room/Store6RoomSchema.kt:8` | * Migration SQL for the adapter-owned TD-6 sidecar. | unclassified | — |
| `store6-room/src/commonMain/kotlin/org/mobilenativefoundation/store6/room/Store6WatermarkEntity.kt:9` | * Adapter-owned TD-6 watermark row. | unclassified | — |
| `store6-room/src/hostTest/kotlin/org/mobilenativefoundation/store6/room/RoomSourceOfTruthReaderSemanticsTest.kt:649` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-room/src/hostTest/kotlin/org/mobilenativefoundation/store6/room/RoomStoreSubstitutionConformanceTest.kt:111` | /** FS-6: disk hydration refetches unconditionally, then same-engine ETag reuse is conditional. */ | unclassified | — |
| `store6-room/src/hostTest/kotlin/org/mobilenativefoundation/store6/room/RoomStoreSubstitutionConformanceTest.kt:189` | /** FS-4 / TD-2: a namespace watermark survives Store replacement and forces a refetch. */ | unclassified | — |
| `store6-room/src/hostTest/kotlin/org/mobilenativefoundation/store6/room/RoomStoreSubstitutionConformanceTest.kt:244` | /** FS-4: clear removes both the user row and its durable freshness record. */ | unclassified | — |
| `store6-room/src/hostTest/kotlin/org/mobilenativefoundation/store6/room/RoomStoreSubstitutionConformanceTest.kt:274` | /** FS-4: clearNamespace runs the user delete and sweeps matching durable metadata only. */ | unclassified | — |
| `store6-room/src/hostTest/kotlin/org/mobilenativefoundation/store6/room/RoomStoreSubstitutionConformanceTest.kt:307` | /** FS-1 / FS-5: StaleIfError serves residence, reports failure, and remains causal-live. */ | unclassified | — |
| `store6-room/src/hostTest/kotlin/org/mobilenativefoundation/store6/room/RoomStoreSubstitutionConformanceTest.kt:378` | /** FR-10 / FS-3: a remote deletion cannot satisfy MustBeFresh on a missing row. */ | unclassified | — |
| `store6-room/src/hostTest/kotlin/org/mobilenativefoundation/store6/room/RoomStoreSubstitutionConformanceTest.kt:400` | /** FR-10 / FS-3: LocalOnly hydrates a user-seeded Room row without fetching. */ | unclassified | — |
| `store6-room/src/hostTest/kotlin/org/mobilenativefoundation/store6/room/RoomStoreSubstitutionConformanceTest.kt:466` | /** FS-3: MaxAge uses durable write time after Store replacement and withholds stale data. */ | unclassified | — |
| `store6-room/src/hostTest/kotlin/org/mobilenativefoundation/store6/room/RoomStoreSubstitutionConformanceTest.kt:56` | /** FS-1 / AC-1: a cold public Store stream persists its fetched value through Room. */ | unclassified | — |
| `store6-room/src/hostTest/kotlin/org/mobilenativefoundation/store6/room/RoomStoreSubstitutionConformanceTest.kt:620` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |
| `store6-room/src/hostTest/kotlin/org/mobilenativefoundation/store6/room/RoomStoreSubstitutionConformanceTest.kt:87` | /** FS-6: a fresh durable row and sidecar let a new Store skip fetching. */ | unclassified | — |
| `store6-room/src/hostTest/kotlin/org/mobilenativefoundation/store6/room/RoomTransactionalSourceOfTruthTest.kt:386` | // Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15). | unclassified | — |

#### `store6-sqldelight` (4 hits at baseline)

| file:line | excerpt | class (P1-P4/FP/Unverifiable) | action taken |
| --- | --- | --- | --- |
| `store6-sqldelight/src/commonMain/kotlin/org/mobilenativefoundation/store6/sqldelight/SqlDelightBookkeeper.kt:35` | * This seam remains FREEZE CANDIDATE awaiting Matt signature. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-sqldelight/src/commonMain/kotlin/org/mobilenativefoundation/store6/sqldelight/SqlDelightSourceOfTruth.kt:35` | * Every user-row mutation and its matching TD-6 metadata mutation execute in one [Transacter] | unclassified | — |
| `store6-sqldelight/src/commonMain/kotlin/org/mobilenativefoundation/store6/sqldelight/SqlDelightSourceOfTruth.kt:62` | * Seam status: FREEZE CANDIDATE awaiting Matt signature; never frozen. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-sqldelight/src/commonMain/kotlin/org/mobilenativefoundation/store6/sqldelight/internal/MetaSidecar.kt:14` | * Adapter-owned durable sidecar (TD-6). Four tables are created and versioned by the adapter in | unclassified | — |

#### `store6-compose` (4 hits at baseline)

| file:line | excerpt | class (P1-P4/FP/Unverifiable) | action taken |
| --- | --- | --- | --- |
| `store6-compose/src/commonMain/kotlin/org/mobilenativefoundation/store6/compose/CollectAsState.kt:26` | * Closed-store behavior (finalized by issue 007): calling this on a closed store fails the | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-compose/src/commonMain/kotlin/org/mobilenativefoundation/store6/compose/StoreResultEquivalence.kt:33` | * issue-007 OQ-1 ruling — same-kind latest-wins, never merged across kinds — whose public | unclassified | — |
| `store6-compose/src/commonMain/kotlin/org/mobilenativefoundation/store6/compose/StoreResultEquivalence.kt:39` | * convenience for stateIn/ViewModel consumers; the engine's TD-8 operator rule | unclassified | — |
| `store6-compose/src/commonTest/kotlin/org/mobilenativefoundation/store6/compose/ClosedStoreBehaviorTest.kt:20` | * exact `Store.stream` seam they call. Close semantics were finalized by issue 007; the close | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |

#### `store6-mutations-sqldelight` (0 hits at baseline)

No baseline hits in this module.

---

## Task 6 — Testing/devtools modules, test sources, unpublished modules

Scope: all baseline hits in `store6-testing`, `store6-mutations-testing`, `store6-devtools`, `store6-devtools-inspector`, and the unpublished modules (`store6-benchmarks`, `store6-quickstart`, `store6-extension-probe`, `store6-compose-demo`, `store6-devtools-demo`) — full per-module lists (Task 2 above resolves the `signs off` stamp subset in `store6-testing` first).


#### `store6-testing` (16 hits at baseline)

| file:line | excerpt | class (P1-P4/FP/Unverifiable) | action taken |
| --- | --- | --- | --- |
| `store6-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/FakeStore.kt:274` | * Close semantics finalized by issue 007. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/FakeStore.kt:40` | * Invalidation implements Decision #37 (Matt, 2026-07-20): it is a stale-mark only and never | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/FakeStore.kt:437` | * Decision #37 (ruled by Matt, 2026-07-20): invalidate is a stale-mark only, the engine's | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/FakeStore.kt:487` | // Finalized by issue 007: core keeps STORE_CLOSED_MESSAGE internal by design (FS-5 — | P2 (Task 2, partial) | Task 2 deleted "Finalized by issue 007: ". The `FS-5` tag on this same line is still present — remains for this task's own sweep/classification. |
| `store6-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/FakeStore.kt:59` | * The seam consumed here is a FREEZE CANDIDATE, not frozen: freeze sign-off remains held until | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/FakeStore.kt:60` | * issue 007 lands and Matt signs off. [close] is synchronous and idempotent. Active collectors are | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/FakeStore.kt:63` | * text were finalized by issue 007 against the engine's close lifecycle. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/testing/SourceOfTruthContractKit.kt:22` | * Conformance kit for [SourceOfTruth] implementations (TD-15). Extend it in your test source set, | unclassified | — |
| `store6-testing/src/commonTest/kotlin/org/mobilenativefoundation/store6/testing/FakeStoreConformanceTest.kt:191` | // THE Decision #37 alignment pin (ruled by Matt, 2026-07-20): with NO active demand, | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-testing/src/commonTest/kotlin/org/mobilenativefoundation/store6/testing/FakeStoreConformanceTest.kt:32` | * There is no invalidate-divergence row: Decision #37 (Matt, 2026-07-20) aligned the fake to the | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-testing/src/commonTest/kotlin/org/mobilenativefoundation/store6/testing/FakeStoreConformanceTest.kt:375` | // Finalized by issue 007: pins verified against StoreCloseLifecycleTest in store6-core. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-testing/src/commonTest/kotlin/org/mobilenativefoundation/store6/testing/FakeStoreConformanceTest.kt:386` | // Finalized by issue 007: pins verified against StoreCloseLifecycleTest in store6-core. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-testing/src/commonTest/kotlin/org/mobilenativefoundation/store6/testing/FakeStoreConformanceTest.kt:83` | assertTrue(ex.message!!.contains("test/1"))            // FS-5: which key | unclassified | — |
| `store6-testing/src/commonTest/kotlin/org/mobilenativefoundation/store6/testing/FakeStoreConformanceTest.kt:84` | assertTrue(ex.message!!.contains("enqueueFetchValue"))  // FS-5: the fix | unclassified | — |
| `store6-testing/src/commonTest/kotlin/org/mobilenativefoundation/store6/testing/UserViewModelSampleTest.kt:45` | fake.enqueueFetchValue(key, User("42", "Matt")) | FP (Task 2) | No change — test-fixture data literal, not documentation. See Task 2 table for detail. |
| `store6-testing/src/commonTest/kotlin/org/mobilenativefoundation/store6/testing/UserViewModelSampleTest.kt:49` | assertEquals("Matt", assertIs<UserUiState.Ready>(awaitItem()).name) | FP (Task 2) | No change — test-fixture data literal, not documentation. See Task 2 table for detail. |

#### `store6-mutations-testing` (1 hits at baseline)

| file:line | excerpt | class (P1-P4/FP/Unverifiable) | action taken |
| --- | --- | --- | --- |
| `store6-mutations-testing/src/commonMain/kotlin/org/mobilenativefoundation/store6/mutations/testing/MutatorPurityContractKit.kt:126` | * Published TD-12 conformance kit for durable mutator projectors. | unclassified | — |

#### `store6-devtools` (2 hits at baseline)

| file:line | excerpt | class (P1-P4/FP/Unverifiable) | action taken |
| --- | --- | --- | --- |
| `store6-devtools/src/commonMain/kotlin/org/mobilenativefoundation/store6/devtools/CompositeStoreTelemetry.kt:15` | * `telemetry(storeTelemetryOf(logger, monitor))`. This is FS-10's multiplex posture: | unclassified | — |
| `store6-devtools/src/commonMain/kotlin/org/mobilenativefoundation/store6/devtools/StoreDevtoolsEvent.kt:13` | * decided: values never cross this seam. The seam remains a freeze candidate and sign-off is held. | P2 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |

#### `store6-devtools-inspector` (0 hits at baseline)

No baseline hits in this module.

#### `store6-benchmarks` (5 hits at baseline)

| file:line | excerpt | class (P1-P4/FP/Unverifiable) | action taken |
| --- | --- | --- | --- |
| `store6-benchmarks/src/main/kotlin/org/mobilenativefoundation/store6/benchmarks/StreamEmissionBenchmark.kt:24` | * METRIC-1: stream-emission overhead versus the raw SoT flow (NFR-8, TD-8, TEST-7). | unclassified | — |
| `store6-benchmarks/src/main/kotlin/org/mobilenativefoundation/store6/benchmarks/SubscriptionChurnBenchmark.kt:21` | * collections — issue 007's OQ-5 explicitly deferred grace tuning (and retry-backoff shape) to | P3 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-benchmarks/src/main/kotlin/org/mobilenativefoundation/store6/benchmarks/TelemetryOverheadBenchmark.kt:24` | * The measured half of FS-10's "zero cost when unset" (008's deferral; StoreTelemetryTest.kt:114). | unclassified | — |
| `store6-benchmarks/src/main/kotlin/org/mobilenativefoundation/store6/benchmarks/TelemetryOverheadBenchmark.kt:26` | * FS-10's evidence is measured plus structural, not a literal differential against a telemetry-free | unclassified | — |
| `store6-benchmarks/src/test/kotlin/org/mobilenativefoundation/store6/benchmarks/TelemetryAllocationProbe.kt:13` | * Allocation evidence for FS-10's measured-plus-structural zero-cost-when-unset claim — the | unclassified | — |

#### `store6-quickstart` (0 hits at baseline)

No baseline hits in this module.

#### `store6-extension-probe` (0 hits at baseline)

No baseline hits in this module.

#### `store6-compose-demo` (2 hits at baseline)

| file:line | excerpt | class (P1-P4/FP/Unverifiable) | action taken |
| --- | --- | --- | --- |
| `store6-compose-demo/src/main/kotlin/org/mobilenativefoundation/store6/composedemo/Main.kt:12` | // Process-scoped store on the landed bounded-registry engine (issue 007): idle key engines | P1 (Task 2) | Handled by Task 2 — see Task 2 table for detail. |
| `store6-compose-demo/src/main/kotlin/org/mobilenativefoundation/store6/composedemo/StabilityProbe.kt:16` | * parameters; the gate posture for these follows the T6 calibration ruling recorded in | unclassified | — |

#### `store6-devtools-demo` (0 hits at baseline)

No baseline hits in this module.

---

## Task 7 — Public-surface KDoc quality audit (alpha01 artifacts)

Not sweep-driven (this is a read-only audit of public-declaration KDoc completeness/quality against the interface-documentation checklist, over `store6-core`, `store6-mutations`, `store6-testing`, `store6-sqldelight`, `store6-room`, `store6-compose`), so no baseline rows apply here. Task 7 appends its own findings table using columns `file:line | class | evidence | remediation` (finding classes: Missing / Stale / Contradictory / Duplicated / Unverifiable / Misleading / Unnecessary / Sufficient) when it runs.

| file:line | class | evidence | remediation |
| --- | --- | --- | --- |
| _(populated by Task 7)_ | | | |


---

## Task 8 — Final gate, Dokka proof, completion report

Reserved for Task 8's completion report (files reviewed/changed, evidence used per kept-contract claim, commands run and results, unresolved `Unverifiable` rows, claims deleted for lack of evidence, proof strength statement). Not populated by Task 1.

