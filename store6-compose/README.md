# store6-compose

Compose Multiplatform integration for Store v6. Everything here is `@ExperimentalStoreApi`.
The seam it consumes is a FREEZE CANDIDATE, not frozen: issue 007 (memory boundedness) has
landed, but the seam-freeze sign-off is not yet signed — the freeze wording flips in a
dedicated follow-up once it is.

## Entry points

- `Store.collectAsState(key, freshness)` — `State<StoreResult<V>>`, starts at `Loading`,
  restarts only on structural identity change (namespace/canonicalId/freshness), all targets.
- `Flow<StoreResult<V>>.collectAsStoreState(initial)` — the flow-level variant.
- `Store.collectAsStateWithLifecycle(...)` / `collectAsStoreStateWithLifecycle(...)` —
  lifecycle-gated via `repeatOnLifecycle`, on all targets. These need a `LifecycleOwner`; on
  targets with no UI host that populates `LocalLifecycleOwner`, pass one explicitly.
- `skipEqualData()` / `storeResultMutationPolicy()` — structural skipping for stateIn/ViewModel
  flows and custom state holders.

## Recomposition discipline

`StoreResult` types deliberately have identity equality. This module skips recomposition by
structural comparison of `Data`'s value/origin/isStale/refreshing — `age` is excluded (it
advances every emission). Results are never merged across kinds. That mirrors the engine's
`conflateLatestData` rule as landed by issue 007 (same-kind latest-wins; never merged across
kinds): "Revalidated is a lifecycle signal: `conflateLatestData` never conflates it away in
favor of another kind; for a blocked collector a newer `Revalidated` supersedes an older queued
one, so the kind itself is never lost." This module is stricter still — `Loading`/`Revalidated`/
`Error` always pass; only structurally-equal consecutive `Data` frames are dropped. Event-shaped
consumption of `Revalidated`/`Error` should collect the Flow, not a State.

## Stability configuration for consumers

Strong skipping (default since Kotlin 2.0.20) compares unstable parameters by instance; this
module's state holders keep instances stable across equal frames, so skipping works out of the
box. To make store types compare as stable values instead — which is what lets the compiler skip
on equal *content* rather than equal *instance* — add the shipped snippet
(`stability/store6-stability.conf`, reproduced below) to your app module:

    composeCompiler {
        stabilityConfigurationFiles.add(
            layout.projectDirectory.file("store6-stability.conf"),
        )
    }

    // store6-stability.conf  (mirror of the shipped file)
    org.mobilenativefoundation.store6.core.*
    org.mobilenativefoundation.store6.core.seam.*

CI verifies this exact snippet against a tiered probe of core public types on every PR. With the
snippet applied, every probed core type — including the interface-typed ones (`StoreResult`,
`Freshness`, `StoreKey`, `StoreMeta`, `StoreError`) and the generic `StoreResult.Data<V>` —
resolves as **stable**; without it, they resolve as `unstable` and the CI gate fails.

Note that `composeCompiler.stabilityConfigurationFiles` is not registered as a Gradle task input
by the Compose compiler plugin, and the emitted stability reports are an undeclared output. This
module's build scripts compensate (`inputs.file(...)` plus opting the demo compilations out of
the build cache) so that editing the conf always re-emits a matching report; consumers relying on
their own report-based checks should do the same.

## Demo

`./gradlew :store6-compose-demo:run` — refreshing spinner-over-content, STALE badge, and
error-with-stale-data against a fake fetcher with toggleable latency and failure.
