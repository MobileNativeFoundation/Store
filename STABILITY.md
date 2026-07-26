# Store 6 stability policy

## 1. What this document is

What each `store6-*` artifact promises, how an API is allowed to change, how often we ship, and how
you can verify all of it from a released tag. Where a promise is not yet earned, this document says
so rather than rounding up.

It is also the standing answer to [#570][570] on binary compatibility and [#534][534] on a published
roadmap.

Scope: the `store6-*` artifacts, effective with the 6.0.0-alpha01 release. Store 5 continues under
its own coordinates, and [§6](#6-migrating-from-store-5) covers living with both.

## 2. API tiers

<a id="tiers"></a>

Store 6 uses three opt-in markers. Each is a real annotation in `store6-core`, and the meaning below
is the one carried in its own KDoc.

| Marker | Means |
|---|---|
| `@ExperimentalStoreApi` | API under active development that **may change or be removed in any release**. Experimental API ships in separate artifacts wherever possible; the marker exists for the cases where an experimental member must live beside stable API. |
| `@DelicateStoreApi` | API that is **stable but easy to misuse** — for example implementing `Store` directly instead of building one through the `store { }` DSL. Opting in asserts that you uphold the documented contract of the marked declaration. |
| `@InternalStoreApi` | API **internal to the Store libraries**. It may change or disappear without notice even in patch releases, and must never be used outside `org.mobilenativefoundation.store` artifacts. |

All three are `RequiresOptIn.Level.ERROR`: you cannot use them by accident. `Store` additionally
carries `@SubclassOptInRequired(DelicateStoreApi::class)`, so implementing the interface yourself is
a deliberate act, not a default.

**Experimental code lives in separate artifacts, never annotation-gated inside a stable one.** When
a capability needs its own release rhythm, it gets its own artifact, and the tier is stated on the
artifact rather than buried in an annotation on a member you have already depended on.

**SemVer is scoped to the stable tier.** A breaking change to an `@ExperimentalStoreApi` surface in
a minor release is not a SemVer violation, because that surface never claimed the guarantee. That is
the whole point of stating the tier on the tin.

## 3. Artifacts and tiers, as of 6.0.0-alpha01

Group coordinates are unchanged: `org.mobilenativefoundation.store`. Packages are
`org.mobilenativefoundation.store6.*`.

| Artifact | Tier | In 6.0.0-alpha01 |
|---|---|---|
| `store6-core` | Stable-track. The API is **not frozen** until the beta01 freeze candidate. | alpha01 |
| `store6-testing` | Experimental (`@ExperimentalStoreApi`) — every public declaration in the artifact carries the marker today. | alpha01 |
| `store6-sqldelight` | Experimental adapter (`@ExperimentalStoreApi`). Graduates to stable at 6.0.0, having run the contract kit throughout the alpha line. | alpha01, may slip one alpha |
| `store6-room` | Experimental adapter, same graduation. | alpha01, may slip one alpha |
| `store6-compose` | Experimental adapter, same graduation. | alpha01, may slip one alpha |
| `store6-mutations` | **Experimental, separate artifact — every public symbol is `@ExperimentalStoreApi`.** See [§8](#mutations). | alpha01 |
| `store6-bom` | Version alignment only; no API surface of its own. | alpha01 |
| `store6-devtools` | Experimental (`@ExperimentalStoreApi`). | alpha02 (target) |
| `store6-devtools-inspector` | Experimental (`@ExperimentalStoreApi`). | alpha02 (target) |

Inside `store6-core`, the `org.mobilenativefoundation.store6.core.seam` package — the 13 files you
implement to plug in your own fetcher, source of truth, bookkeeper, clock, telemetry, or overlay —
is a **freeze candidate, not frozen.** Today these types are `@ExperimentalStoreApi`, so
implementing one is an explicit opt-in; that is the exception §2 names, and it is why the seam sits
inside a stable-track artifact rather than shipping separately.

The candidate-versus-frozen distinction is load-bearing and we state it in two stages deliberately.
A real producer has to exercise a seam end to end before we will call it a candidate. The
`Overlay` and `StoreWriteHandle` surfaces become frozen only once the ack-path atomicity work and
its test matrix are green; if that work misses beta01, those two ship `@ExperimentalStoreApi`
outside the frozen tier and the rest of core freezes on schedule. CI enforces the 13-file list on
every pull request, so the seam cannot grow quietly.

Promised but not in the alpha01 line: `store6-store5-interop` and `store6-paging-androidx`, both
tracking to 6.0.0. An artifact that misses a train gets its target release named here. It does not
get dropped silently.

## 4. Deprecation cycle

<a id="deprecation"></a>

Every removal from the stable tier goes through three stages:

1. **`WARNING` with `ReplaceWith`.** The replacement is mechanical wherever the shape allows it.
2. **`ERROR`, no earlier than two minor releases later.** You get at least two minors of warning
   before your build breaks.
3. **`HIDDEN` at the next major.** Binary compatibility is preserved until then.

**No silent capability drops.** A removed capability gets the same cycle and a migration note. You
should never find out a capability is gone by upgrading.

## 5. Release cadence

<a id="cadence"></a>

**Monthly alphas from 6.0.0-alpha01.** The governing rule is **cut scope, never cadence**: a slip
threatens a release's contents, never its date. If something is not ready, it ships in the next
alpha a month later and the release notes say so.

We will not repeat a 30-month alpha line, and we will not break API in beta again.

Each alpha closes at least one community issue with a link to the named guarantee that resolves it —
a conformance test, not a changelog line. The next alpha's target month is stated in each release's
notes. This document states the policy. Each release states the date.

The public roadmap is at [ROADMAP.md](./ROADMAP.md).

## 6. Migrating from Store 5

`store5.*` and `store6.*` coordinates live **side by side for the whole 6.x major**. You can depend
on both in one build and migrate a screen at a time. There is no flag day.

`store6-store5-interop` is supported for all of 6.x. The 5→6 and 4→6 migration guides are launch
gates for 6.0.0 — they block GA, they are not follow-ups.

## 7. How stability is verified

<a id="verification"></a>

Every claim in this document is checkable from a released tag.

- **`explicitApi()` strict** on every `store6-*` library module. Nothing becomes public by omission.
- **Binary-compatibility-validator (0.17.0) with klib validation enabled.** Each module commits a
  JVM `.api` dump and a `.klib.api` dump — for example `store6-core/api/jvm/store6-core.api` and
  `store6-core/api/store6-core.klib.api`. The check runs as part of `build` on every pull request,
  so an unintended ABI change fails CI before review.
- **Generated-Swift dumps diffed on every pull request** across the supported bridges — Obj-C export
  and SKIE today (`store6-core/api/swift/objc`, `store6-core/api/swift/skie`). The bridge set follows
  the Swift Export disposition recorded at the alpha01 cut, so read this as a commitment to the
  mechanism rather than to a fixed list of lanes.
- **ABI dumps are committed at every released tag**, so the surface of any release is diffable from
  the repository without resolving artifacts.
- **The conformance suite is public documentation of what is guaranteed.** The behaviors this
  library promises are named tests you can read:
  [`store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/`](store6-core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/)
  (`*ConformanceTest.kt`). When a release closes one of your issues, the notes link the test, not a
  bullet point.

## 8. Mutations at 6.0.0-alpha01

<a id="mutations"></a>

`store6-mutations` is in the alpha01 floor, not the may-slip list: an app that writes should not
have to wait for a later alpha. Three things about it are worth stating plainly.

### (a) The tier

Experimental, in its own artifact, every public symbol `@ExperimentalStoreApi`. The written
graduation criteria are published alongside the 6.0.0-alpha01 release and linked from this section
then; the first review is at 6.1. The target window for graduation to stable is roughly 6.3, and it
is a target rather than a schedule: graduation requires the API unchanged across two consecutive
minors,
crash-matrix and soak lanes green in production-representative apps, and at least three external
production adopters reporting. If those are not met, it stays experimental and the review repeats.
Nothing graduates because a date arrived.

### (b) The two-step durable ack posture

When a mutation is acknowledged by the server, the alpha writes the echo through and retires the
journal row in two steps rather than one atomic step: **it adopts the server echo first and retires
the journal row last.** The transactional path does the opposite — write and retire inside one
transaction, then adopt — and it is safe there precisely because it is atomic. The non-transactional
fallback has no atomicity, so it must invert the order: **a crash before retire leaves a replayable
pending intent, while a crash after an early retire loses the write outright.**

This is the same conservative crash-window stance already ratified for reads: prefer doing work
twice over losing it.

The consequence, stated so you meet it in this document rather than in production: because restart
replay re-pushes pending intents, **a crash inside that ack window can result in the same push being
re-sent.** Design your server endpoints for the mutations you care about to be idempotent, or key
them by the mutation identity.

Closing that window — making the ack path atomic — is beta01 work, not alpha01 work.

### (c) The surface is under review

The mutations entry point is provisional. The API review that ratifies its final spelling has not
run yet, and the factory signature in particular is **expected** to change: the current shape hides
which persistence a caller installed, which the transactional ack-path decorator will need to see.
Treat every mutations snippet in these docs as illustrative of the shape, not as a signature to
depend on. This document deliberately freezes no mutations signature into policy prose.

## 9. Reading pending writes and staleness

Two affordances that look similar are not, and getting them backwards produces UI bugs that are
hard to trace.

- **A "pending write" affordance keys on `origin == OVERLAY`.**
- **A "stale cache" affordance keys on `isStale`.**

`isStale` is **never set on an `OVERLAY` frame.** Overlay frames are fresh by definition: they are
stamped `age = Duration.ZERO` and `isStale = false` unconditionally, because an optimistic value
genuinely is new — the user just wrote it. On an overlay frame, only `refreshing` is live. So a
spinner driven by `isStale` will never fire for a pending write, and that is intended. Drive the
pending-write indicator off the origin and narrate the `OVERLAY` → `SOT` flip.

**`Store.get` is unprojected.** Overlays apply only to `stream`, so an optimistic mutation is
invisible to `get`. This is a documented consequence of the read contract, not a defect: `get` is a
point read of committed truth. If you need to observe your own optimistic write, observe `stream`.

## 10. Kotlin floor

The `store6` line requires **Kotlin 2.3**, raised only in minor releases and with notice.

The floor is what the published artifacts actually imply, not an aspiration: every published
`store6-core` variant — JVM, Android, JS, wasmJs, and each native target — declares
`org.jetbrains.kotlin:kotlin-stdlib:2.3.20`, and the build sets no `apiVersion` or `languageVersion`
compatibility pin that would lower it. Room 3 is what drove the toolchain here.

[570]: https://github.com/MobileNativeFoundation/Store/issues/570
[534]: https://github.com/MobileNativeFoundation/Store/issues/534
