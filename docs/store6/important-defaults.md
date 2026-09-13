# Important defaults

A zero-config `store { fetcher { … } }` (see the [Quickstart](quickstart.md)) already makes a lot
of decisions for you. This page names
every one of them, so you can find out here rather than in production.

Each line ends in the conformance test that guarantees it. Those tests are the specification — if a
line here and its test ever disagree, the test is right and this page is a bug.

> **Zero configuration and explicit expert configuration are byte-identical in behavior.** Setting
> the defaults by hand changes nothing observable: both sides produce the same trace and the same
> fetch count (`zeroConfig_and_expertConfig_observeIdenticalDefaults`). One honest limit on that
> guarantee: the equivalence is asserted over persistence, bookkeeper, freshness validator, and idle
> cap. It does not cover telemetry or overlay, which are unset on both sides.

## Freshness

See [Freshness policies](/docs/store6/concepts/freshness) for the complete per-call policy contract.

- **The default is `Freshness.CachedOrFetch`.** An absent key fetches; a resident fresh value is
  served without a second fetch (`defaultFreshness_isCachedOrFetch_zeroConfig`).
- **`MaxAge` within its bound serves the resident value without a second fetch**
  (`maxAgeWithinBoundServesResidentWithoutSecondFetch`).
- **`MustBeFresh` always fetches**, even against a fresh resident value
  (`mustBeFreshRefetchesFreshResident`).
- **`LocalOnly` never fetches.** It serves what is resident
  (`localOnlyResidentIgnoresInvalidationForGetAndStream`), and when nothing is resident it fails
  `Missing` without a `Loading` frame and without calling the fetcher
  (`localOnlyWithoutResidentReportsMissingWithoutLoadingOrFetcherCall`).
- **Stale-while-revalidate is the shape of every refresh.** A stale value is served immediately and
  the refresh produces **exactly one** terminal outcome — one fresh `Data`, or one served-stale
  `Error`, or one `Revalidated`, never two
  (`ac1a_staleWhileRevalidate_successEmitsStaleThenExactlyOneFreshData`, and its `ac1b`/`ac1c`/`ac1d`
  siblings). A `get` on a stale resident value serves the stale value and refetches in the
  background (`getOnStaleResident_servesStaleThenRefetchesInBackground`).

## Retry

- **The engine does not retry your fetcher. Zero retries, zero backoff, at zero configuration.** One
  demand cycle invokes the fetcher exactly once, and a failure schedules no background retry — on
  the terminalizing `get` path and on a live `stream` collector that survives the failure. A later
  call is new demand rather than a continuation of the failed one
  (`fetcherFailure_isNotRetried_zeroConfig`, which pins all three). If you want retries, they belong
  in your fetcher, where you control the policy.
- **The source-of-truth reader subscription self-heals.** If the reader pipeline drops, the engine
  re-subscribes on a fixed internal delay. This is engine behavior, not a knob: **the delay constant
  is internal and not contractual**, and no test pins its literal value.

## Cache and memory

See [Memory and lifecycle](/docs/store6/concepts/memory-and-lifecycle) for eviction and
store-lifecycle guidance.

- **In-memory source of truth and in-memory bookkeeper by default.** Nothing is written to disk
  until you install persistence (`StoreBuilder.kt:180`, `StoreBuilder.kt:52`).
- **Idle residency is capped at 128 keys.** Quiescent engines park in an idle set bounded by that
  cap, and the zero-config cap is the same as an explicit `maxIdleKeys(128)`
  (`defaultMaxIdleKeys_matchesExplicit128Cap`, `quiescentKeys_parkInIdle_boundedByMaxIdleKeys`).
- **Eviction touches only quiescent engines.** A key with an active collector or an in-flight fetch
  is never evicted, and residency stays bounded under churn
  (`churn10kKeyCycles_neverEvictsHeldEngines_andResidencyStaysBounded`,
  `activeCollector_pinsEngine_acrossChurn`).
- **Eviction is semantically invisible.** Destroying and recreating an engine preserves per-key
  stale marks and namespace watermarks, and still drives the refetch you would have gotten
  (`evictedEngine_recreation_semanticallyInvisible`).
- **Invalidation watermarks survive restart and eviction.** A namespace or global invalidation is
  observed by a key a fresh store has never seen
  (`invalidateNamespace_watermarkIsObservedForKeyUnseenByFreshStore`).
- **The memory cache never diverges from durable truth** (`memoryCache_neverDivergesFromDurableTruth`).

## Deduplication and single-flighting

- **N concurrent callers share one fetch.** 50 getters and 50 collectors demanding the same key
  produce exactly one fetch, and all 100 observe its outcome
  (`ac2_fiftyGettersAndFiftyCollectorsShareOneFetch`).
- **A stream arriving during an in-flight fetch piggybacks it** rather than starting a second one
  (same test, plus `cancelledWaiterDoesNotCancelSharedFetch`).
- **Cancelling a waiter does not cancel the shared fetch.** The work commits and the next caller
  reuses it (`cancelledWaiterDoesNotCancelSharedFetch`,
  `getAfterStreamCommitted_servesResidentValueWithoutRefetch`).

## Emission

See [the read contract](/docs/store6/concepts/read-contract) for the complete result-kind and origin
semantics.

- **Attribution is honest.** A network commit is `Origin.FETCHER`
  (`preSubscribedCollectors_waitThroughQueuedAbsentThenDeliverWriterCurrentEcho`); an external
  durable change is `Origin.SOT` (`externalWriteReturned_whileGraceHasOldMemory_convergesMemoryThenSot`);
  an optimistic write is `Origin.OVERLAY` (`modifyingOverlay_stampsOverlayOrigin`); and a write-handle
  `apply` echo is attributed `Origin.SOT` with no additional fetch
  (`writeHandle_apply_emitsSotData_withoutFetch`).
- **A slow collector never blocks a fast one, or the engine**
  (`slowCollector_doesNotBlockFastCollector_orEngine_andIsBoundedPerCycle`).
- **Conflation is per result kind, and lifecycle signals are never dropped.** A newer value of the
  same kind supersedes an older queued one, but another kind never displaces a queued `Loading`,
  `Error`, or `Revalidated` (`slowCollector_getsLatestDataAndEveryLifecycleSignalBeforeCompletion`).
  Every collector eventually observes the latest row (`everyCollector_eventuallyObservesLatestRow`).
- **A `NotModified` response surfaces as exactly one `Revalidated(age)`** and clears staleness, not
  as a redundant `Data` frame (`conditionalRefetch_notModified_emitsOwnerRevalidatedAndClearsStaleness`).
- **A post-clear stream starts absent or loading and never replays pre-clear data**
  (`clear_thenNewStreamEmitsLoadingNeverStaleReplay`,
  `clearNamespace_thenNewStreamEmitsLoadingNeverPreClearData`). A clear racing an in-flight fetch
  cannot resurrect the discarded commit (`clearDuringInFlightFetch_commitDiscarded_noResurrection`).
- **Invalidation reaches live streams**, and survives a 10,000-invalidation burst without losing the
  final staleness (`invalidate_activeStream_observesRefetchedData`,
  `invalidate_burstOf10k_convergesWithoutLosingFinalStaleness`).

## Reader grace

A re-subscribe within a short window after the last collector leaves resumes the existing pipeline
rather than starting over with a fresh `Loading` frame. The behavior is exercised by the suite. The
window's millisecond value is an internal constant, and this page deliberately does not pin it.

## What is *not* defaulted

- **A fetcher is required**, and installing a source of truth does not substitute for one.
  `store<K, V> { }` without `fetcher { }`, `fetcherOfResult { }`, or `fetcher(Fetcher)` fails at
  build time with a message that says so (`StoreBuilder.kt:176-179`).
- **Telemetry is unset**, and the engine takes a null fast path rather than paying for a no-op sink
  (`StoreBuilder.kt:58`).
- **Overlay is unset.** With no overlay installed, nothing is projected onto reads
  (`StoreBuilder.kt:61`).

---

*Every test named above lives under
[`core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/`](../../core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/).*

*Last verified: 2026-08-10 · `main` @ `a6a156e9`, pre-6.0.0-alpha01*
