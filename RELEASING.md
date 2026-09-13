Releasing
========

Store 6 follows the release policy in [STABILITY.md](./STABILITY.md). `VERSION_NAME` in
[`gradle.properties`](./gradle.properties) is the single version source. The publication
manifest, BOM, and STABILITY release column must describe the same shipping artifacts.

## Preparing a release

1. Select the release version, release date, next alpha target, and community issue resolved by
   a named conformance guarantee. Prepare the matching `CHANGELOG.md` section. Immutable releases
   require exactly one `## [VERSION] (YYYY-MM-DD)` heading and nonempty notes without unfinished
   placeholders. Draft notes are not publication evidence.
2. Set the root `VERSION_NAME`. A release tag requires a version without `-SNAPSHOT`; manual
   CI dispatch accepts snapshots only. Module properties must not override the root version.
   Local publication verification reads this same property, so there is no second version to edit.
3. Regenerate changed modules' JVM, Android, and KLIB ABI output with their `apiDump` tasks.
   If Swift-facing source changed, run `./gradlew refreshSwiftDumps`, inspect generated output,
   and run `./gradlew checkSwiftDumps`. Commit generated output with its source changes.
4. Validate the candidate's complete matrix, publication metadata, examples, and external
   consumers. Keep the source revision, command, environment, task outcome, test identifiers,
   failures, skips, and artifact inventory with the candidate. Compilation, KLIB file existence,
   cached results, and fresh test execution are different evidence classes.
5. Open the release pull request. Changes listed in `.github/docs-sync-sources.txt` require the
   `docs-sync-ack` label. The label acknowledges the synchronization work; it does not establish
   that the documentation site has synchronized. Verify the site separately before claiming it
   is ready.
6. After release-owner approval, merge and tag the release commit, then push the tag to
   `MobileNativeFoundation/Store`. The tag must be exactly `v${VERSION_NAME}`. A new source,
   configuration, or version change requires validation at that new revision.

## Publication gate

[CI](./.github/workflows/ci.yml) permits publication only in `MobileNativeFoundation/Store`.
It requires the root build, release-workflow fixtures, all six jobs in the reusable
[Store6 matrix](./.github/workflows/store6.yml), and the
[full mutations suite](./.github/workflows/store6-full-jvm.yml). The PR-only documentation
acknowledgment check is not a release-tag job. Required jobs must succeed at the checked-out
source SHA, workflow run, attempt, and root version before publication begins. Every required
job records those values. The matrix and full-suite censuses reject missing or mismatched job
records, so reusing success from an earlier attempt cannot authorize publication.

The full mutations suite executes once, split across five jobs on `ubuntu-latest`:
`full-mutations-jvm` runs `:mutations:jvmTest`, which carries every test class except the
model-checking one, and the four-shard `lincheck` matrix runs `:mutations:lincheckTest` over the
101 scenarios of the pinned plan (100 generated from the seed plus one curated), split round-robin
by index across the four shards (26/25/25/25). The matrix does not fail fast, and every lane must
pass. Each lane records its own execution with
`release_control.py full-suite-execution`; `validation-evidence` aggregates those records with
`release_control.py full-suite`.

The JVM test task disables cache and up-to-date reuse when `store6.fullJvmSuite` is set;
`lincheckTest` disables both unconditionally, and `jvmTest` always excludes the Lincheck class.
Compilation caching remains available. Each result artifact records task outcome, executed test
identifiers, XML hashes, and run provenance, and each record also carries its task, its shard,
its executed class list, and, for a shard, its scenario indices. The gate proves that the shards
cover scenarios 0 through 100 exactly once and that the Lincheck class never ran in the jvmTest
lane. Missing, cached, incomplete, and failed test evidence cannot satisfy the gate. A first
failure is retained in that Actions run's summary and result artifact for classification, not
rerun unchanged for green.

Beyond that scenario-coverage union, two more checks run per shard. Every shard's
`store6-lincheck-scenarios` marker line prints a plan digest that `release_control.py` requires to
equal its pinned `LINCHECK_SCENARIO_DIGEST`, which
[`test_workflow_contract.py`](./.github/scripts/tests/test_workflow_contract.py) in turn pins
equal to `SCENARIO_DIGEST`, the golden constant in
[`LincheckScenarioPlan.kt`](./mutations/src/jvmTest/kotlin/org/mobilenativefoundation/store6/mutations/LincheckScenarioPlan.kt).
A shard that validated a different plan — a changed `SCENARIO_SEED`, curated scenario, or
thread/actor shape — fails the gate instead of passing quietly. Separately, Lincheck's own
`= Iteration k / n =` lines are counted out of the console log and must equal the shard's planned
scenario count, so a shard that stopped short of its plan — a hang or an early abort — fails even
where the Gradle task itself reported success.

When the plan legitimately changes — a new `SCENARIO_SEED`, a different curated scenario, or a
changed thread/actor shape — regenerate the digest from the new plan and update the constant in
both `LincheckScenarioPlan.kt` (`SCENARIO_DIGEST`) and `release_control.py`
(`LINCHECK_SCENARIO_DIGEST`); `test_workflow_contract.py` fails the build until the two agree
again.

Both checks depend on Gradle test output that carries no other role in the build:
`testLogging.showStandardStreams` in [`mutations/build.gradle.kts`](./mutations/build.gradle.kts)
and `.logLevel(LoggingLevel.INFO)` in
[`MutationJournalLincheckTest`](./mutations/src/jvmTest/kotlin/org/mobilenativefoundation/store6/mutations/MutationJournalLincheckTest.kt)
are gate inputs, not incidental verbosity — removing either blinds the evidence recorder to the
marker or iteration lines it depends on.

Budget the wall-clock cost from the first hosted execution of this gate, measured on
`ubuntu-latest` on 2026-09-11: 51 minutes end to end, with the jvmTest lane under 2 minutes and
the shards between 36 and 51 minutes.

A Lincheck `Unable to transform` diagnostic in the console log or XML `system-err` output
also rejects the run when test cases pass: the affected class may have run without model-checking
instrumentation. Inspect the preserved diagnostic before changing or repeating the candidate.

Local publication verification requires the consumable artifact, POM, and Gradle module metadata
for every expected module and target publication. Missing or unexpected target publications fail
validation.

The publication controller reads the shipping modules from
[`.github/release-manifest.json`](./.github/release-manifest.json), which lists fifteen libraries
plus the BOM at this revision. Immutable publication uses `publishAndReleaseToMavenCentral`;
snapshots use `publishToMavenCentral`. The rest of the publication metadata also comes from the
root [`gradle.properties`](./gradle.properties): the group, the version, and the
`mobilenativefoundation` / Mobile Native Foundation developer identity written into every
generated POM. Credentials and signing material come from CI secrets. Local fixtures exercise
these steps with command stubs and do not establish signed Central deployment.

Before immutable Maven publication, CI reserves a draft GitHub Release for the tag. It records
an attempted module before invoking Maven and appends each completed module to
`publication-receipt.json`. The receipt, validation evidence, and notes are uploaded as an
Actions artifact before the GitHub Release is made public. Existing release records block
automatic publication of the same immutable version again.

## Partial publication and record repair

Inspect the original run's receipt before taking another publication action. A failed command
can leave Central state uncertain; reconcile the attempted module with Central and the recorded
inventory. Do not treat a failed workflow as proof that no artifacts were released. Published
Maven versions are immutable, and removing a GitHub record does not undo Maven publication.

When the receipt records every shipping module as complete but the GitHub record failed, run
[`Store6 release record repair`](./.github/workflows/store6-release-record.yml) with the release
tag, original CI run ID, and original attempt. It checks the tag/source and downloads that
attempt's preserved receipt. It only uploads the receipt and updates GitHub notes and release
visibility; it never invokes Maven. Repeating this record repair is supported. Incomplete or
missing receipts require reconciliation before repair and are rejected by this workflow.

Full-suite results and release provenance/receipt artifacts are retained for 90 days. Ordinary
matrix and root-build failure reports are retained for 7 days; preserve those raw reports before
day 7. Artifact resolution from Central and the final GitHub record must be checked before
announcing availability or replacing prerelease-only installation text.

## After the release

Set the root `VERSION_NAME` to the next development version. Preserve the released source SHA,
artifact and BOM/POM inventory, consumer evidence, release receipt, notes, and known limitations.
Keep the selected next-alpha target in the published notes.
