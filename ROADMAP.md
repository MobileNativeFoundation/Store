# Store 6 roadmap

Store 6's plan, with dates on it. Some of those dates will move. What will not move is the rule
that governs how they move, stated in the first section below. Where a window is an estimate, this
page says so and gives the range.

This is the roadmap [#534][534] asked for.

## Operating principles

These are commitments, not aspirations.

1. **Cut scope, never cadence.** A slip threatens a release's contents, never its date.
2. **The read core never waits on an extension.** If paging, the Swift facade, or Store 5 interop
   slips, 6.0 still ships as a complete, stable read library without it. Mutations are not an
   extension for this rule's purposes — writing is functionality Store 5 already shipped, and Store 6
   is not publishable to this community without it. It lives in a separate artifact because its API
   is experimental, which is a packaging decision, not a dependency one.
3. **Experimental code lives in separate artifacts.** Never annotation-gated inside a stable
   artifact, so a tier is always visible on the thing you depend on.
4. **Docs are launch gates, not follow-ups.** A release without its documentation is not done, and
   migration guides ship with the migration.
5. **Gates are written down before the work starts**, so a feature ships when its criteria pass
   rather than when enthusiasm peaks.

## Release train

### Foundation (Q3–Q4 2026)

The build, the target matrix, the CI lanes, and the API-review discipline, proven end to end before
depth is added. Binary-compatibility and generated-Swift dumps gated in CI from the first alpha.
Store 6 is developed in a fork and lands in this repository under `store6.*` before the alpha01 cut.
History, stars, and watchers stay here.

### 6.0.0-alpha01 — target Q4 2026 (confidence range Q4 2026 – Q1 2027)

The list is split into a floor that defines the release and deliverables that may slip a month
under principle 1. The confidence range above is real: treat Q1 2027 as the honest outer bound.

**The floor — these are alpha01:**

| | |
|---|---|
| `core`, `testing` | The engine and its conformance kit. |
| `mutations` | The write path: journal, drain, rebase, conflict stack, restart replay. Experimental artifact, in the floor rather than the may-slip list. |
| STABILITY.md + this roadmap | The published policy: tiers, deprecation cycle, cadence commitment. |
| Quickstart + Important Defaults | The mental model before the API reference. |

**May slip one alpha:** the SQLDelight, Room, and Compose adapters, the devtools MVP, and the
remaining documentation pages. Anything that slips gets its target alpha named in the release notes.

### Mutations beta train + 6.0.0-beta01 (Q1–Q2 2027)

Ack-path atomicity and its crash matrix, the Paging 3 interop adapter, the Swift SPM facade against
the freeze-candidate core, the outbox inspector demo, and Store 5 interop with migration lint.

beta01 is the **core API freeze candidate**. From beta01 forward, no source-breaking core change
without an RC reset.

A word on what "freeze" means here, because it is the promise most worth being precise about. The
seam you implement to plug in your own fetcher, source of truth, bookkeeper, clock, telemetry, or
overlay becomes a freeze **candidate** once a real producer has exercised it end to end, which the
mutations work does before alpha01. It becomes **frozen** only after the ack-path atomicity work and
its test matrix are green. If that work misses beta01, the overlay and write-handle surfaces ship
experimental outside the frozen tier and the rest of the core freezes on schedule. Two stages, both
stated, neither skipped.

### 6.0.0 GA — target Q3 2027 (confidence range Q3 – Q4 2027)

Core, testing, the adapters, Store 5 interop, and the BOM in the stable tier, the adapters having
run the contract kit throughout the alpha line. Paging ships alongside as a supported experimental
artifact with the tier on the tin. The 5→6 and "Store 4 → 6 in an afternoon" migration guides both
block GA. Store 5 moves to fixes-only maintenance with a dated end-of-life published at GA.

### After GA

6.1 brings the first mutations graduation review. 6.2 is gated rather than dated. 6.3 is the target
window for mutations graduation to stable.

## Cadence

**Monthly alphas from 6.0.0-alpha01.** Each release names the next release's target month, and each
one closes at least one community issue with a link to the named guarantee that resolves it — a
conformance test, not a changelog line.

Full policy, including the deprecation cycle and how to verify any of this from a released tag, is
in [STABILITY.md](./STABILITY.md).

## Mutations graduation

Mutations stay experimental past GA. The first review is at 6.1, and the target window for
graduation is roughly 6.3. Graduation requires the API unchanged across two consecutive minors,
crash-matrix and soak lanes green in production-representative apps, and at least three external
production adopters reporting. If those are not met, it stays experimental and the review repeats.
There is no date-driven graduation.

## How to contribute

- **Documentation.** Every page in this line names the source it was written from, and code blocks
  come from modules CI compiles. If a page loses you, open an issue saying where. That is a useful
  bug report, and it is the one we most want.
- **Semantics.** The conformance suite under
  [`core/src/commonTest`](core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/)
  is the specification. If you can describe a behavior you expected and a test that would have
  caught it, that is a complete contribution before a line of implementation.
- **Adapters and platforms.** The source-of-truth seam is small on purpose. An adapter for a store
  we do not cover is a self-contained contribution.
- **Where to talk.** The [#store](https://kotlinlang.slack.com/archives/C06007Z01HU) channel on
  Kotlin Slack, or an issue on this repository.

Issues that name a concrete expectation get answered with a test. That is the on-ramp.

[534]: https://github.com/MobileNativeFoundation/Store/issues/534
