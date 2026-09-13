# Changelog

### Thank you to all our wonderful contributors and users

## [6.0.0-alpha01] (unreleased; date pending)

The first Store 6 alpha. Store 6 is the next major line, a Kotlin Multiplatform library for reading
and writing data that lives in more than one place: a network, a local database, and memory. The
stability policy is in [STABILITY.md](./STABILITY.md); each artifact's tier is stated there.

**New Features**

* The alpha roster contains stable-track `core`, experimental `testing`, `mutations`,
  `mutations-sqldelight`, `mutations-testing`, `mutations-conflicts`, `sqldelight`, `room`,
  `compose`, `graphql`, `realtime`, `file`, `ktor`, `opentelemetry`, and `paging-androidx`, plus
  `bom` for version alignment. Other modules remain deferred as listed in
  [STABILITY.md](STABILITY.md); a passing build does not change release eligibility.
* `KtorExchange`'s constructor is public, so a `KtorErrorMapper` can be unit-tested without
  driving a fetch.
* The OpenTelemetry instrumentation-scope version is generated from the build version; a
  hardcoded constant would otherwise have reported `6.0.0-SNAPSHOT` regardless of the version
  actually published.
* With durable journal storage, recovery from a committed `ACKED` receipt resumes source adoption,
  effects, and retirement without another push. A crash before that receipt commits can resend
  the same generation. Endpoints must treat a repeated idempotency key as the same request;
  the default in-memory journal does not survive process death.
* A conformance suite under `core/src/commonTest` names every zero-config behavior as a readable
  test, including single-flight, freshness policy, overlay projection, invalidation, and engine
  eviction.

**Bug Fixes**

* Settle admitted SQLDelight mutations and their reader notifications before returning when the
  caller is cancelled. Explicit failures before commit still roll back.
* Park value-codec failures during initial mutation preparation and precondition copies without
  counting a push attempt, and release completed execution caches after safe retirement.
* Cancel suspended `Store.get` work when the Store closes.
* Report bookkeeping read failures through Store's typed persistence errors and keep freshness
  conservative until successful revalidation. SQLDelight and Room status reads preserve storage
  failures instead of treating them as fresh metadata.
* Settle admitted Room bookkeeping transactions across caller cancellation.
* Bind acknowledgement content and metadata to the same writer, preserve invalidations after
  the first push, and retain conservative freshness when earlier evidence is unavailable.
* Exclude an acknowledged optimistic prefix only after proven source adoption; queued suffixes
  and durable recovery records remain available.
* Keep a committed fetch behind its causal source-reader observation when a suspended absence
  replan resumes during the bookkeeping tail.
* Preserve a captured writer's source origin when a later writer changes reader resolution.
* Reject nonfinite GraphQL floats, normalize signed zero, and preserve distinct integer and float
  canonical identities. See the [persisted-key migration note](graphql/README.md#persisted-numeric-keys).
* Fix `Store.get` surfacing a joined-fetch failure when a concurrent write already committed a
  fresher value; the resident value is served instead.
* Fix invalidation telemetry and key events firing for invalidations that were superseded before
  signaling.
* Fix a silent wedge when a mutation `stales` function throws: the intent now parks durably with a
  dead-letter row instead of blocking its key's queue invisibly.
* Fix cross-namespace canonical acknowledgements looping forever: alias rejection is terminal and
  the accepted generation is never re-pushed after parking.
* Make post-acknowledgement codec blocks visible through the event stream while the execution stays
  `ACKED` without re-pushing.
* Fix nested writes across different Room databases silently deadlocking on opposite stripe order;
  admission now fails fast with an exception naming both databases.
* Stop adapter bookkeepers from swallowing `OutOfMemoryError` and other VM failures as "stale".
* Replace quadratic UTF-8 truncation in the in-memory journal storage with a single pass.
* Refuse `204 No Content` and `205 Reset Content` in the Ktor kit's default mapping instead of
  adopting them; neither carries a representation, so an empty body never replaces a resident
  value. Handle those statuses in a `KtorErrorMapper`.
* Refuse a `KtorOutcome.NotModified` returned by a mapper for an exchange that sent no validator:
  freshness cannot be refreshed from an unconditional request.
* Refuse a `304 Not Modified` whose request carried a validator header the Ktor kit did not set.
  The kit cannot tell which validator the server compared, so it fails closed rather than
  trusting the response.
* Default the paging refresh key to the previous key of the page closest to the anchor, so a
  forward-only source restarts from the initial page instead of resuming at the next page and
  skipping the anchored content.
* Reject a `file` namespace or canonical id holding an unpaired surrogate before any file or
  mirror change. UTF-8 encoding replaces an unpaired surrogate with U+FFFD, so distinct malformed
  keys would otherwise map onto one on-disk name.

**Known limitations**

* The zero-config in-memory source of truth and bookkeeper retain one entry per distinct key for
  the store's lifetime and are not bounded by `maxIdleKeys`. Install persistent implementations
  when key cardinality can grow without limit; bounded defaults are tracked for the beta line.
* A `clearNamespace` or `clearAll` call racing demand that starts during the sweep can let that
  demand commit into the cleared namespace after the call returns. Tracked for beta01.
* On a long-lived `stream(MaxAge)` collection started against fresh data, nothing replans when the
  value ages past the window until another event touches the key. Drive refreshes from your own
  timer if this matters.
* An overlay frame's `refreshing` bit can lag one projection cycle behind a fetch that started
  after the identical overlay value became visible. It self-corrects on the next distinct frame.
* Room echo publication backpressures writers behind a stopped collector instead of dropping the
  mutation; one wedged collector freezes writes to its database. This tradeoff is documented at
  the adapter.

**Community issues**

* Closes [#402](https://github.com/MobileNativeFoundation/Store/issues/402). `Freshness.MustBeFresh`
  refetches even when the resident value is fresh: `mustBeFreshRefetchesFreshResident` in
  [FreshnessPolicyConformanceTest](core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/FreshnessPolicyConformanceTest.kt).
* Closes [#536](https://github.com/MobileNativeFoundation/Store/issues/536). `Freshness.LocalOnly`
  serves a pre-populated source of truth without calling the fetcher:
  `localOnly_prePopulatedSot_getServesWithoutFetcher` in
  [SourceOfTruthConformanceTest](core/src/commonTest/kotlin/org/mobilenativefoundation/store6/core/SourceOfTruthConformanceTest.kt).
* Closes [#702](https://github.com/MobileNativeFoundation/Store/issues/702) and
  [#602](https://github.com/MobileNativeFoundation/Store/issues/602). `paging-androidx` builds an
  androidx `PagingSource` and a `RemoteMediator` over any Store: `refreshLoad_mapsFirstDataFrameToPage`
  and `appendLoad_usesPageKeyFromParams` in
  [StorePagingSourceTest](paging-androidx/src/commonTest/kotlin/org/mobilenativefoundation/store6/paging/StorePagingSourceTest.kt),
  and `mediatorRefresh_invalidatesThenGetsFresh` and `mediatorAppend_getsCachedOrFetch` in
  [StoreRemoteMediatorTest](paging-androidx/src/commonTest/kotlin/org/mobilenativefoundation/store6/paging/StoreRemoteMediatorTest.kt).
* Answers [#534](https://github.com/MobileNativeFoundation/Store/issues/534) with the published
  roadmap, [ROADMAP.md](ROADMAP.md).
* Answers [#570](https://github.com/MobileNativeFoundation/Store/issues/570) with the committed
  JVM and KLIB ABI dumps described in [STABILITY.md](STABILITY.md#verification), which are
  committed at every released tag and checked on every pull request.
* Answers [#722](https://github.com/MobileNativeFoundation/Store/issues/722) and
  [#578](https://github.com/MobileNativeFoundation/Store/issues/578) with the mutations floor in
  this alpha — `mutations`, `mutations-sqldelight`, and `mutations-testing` — described in
  [STABILITY.md](STABILITY.md#mutations).

The release date and the next-alpha target month await the release owner; the target month is
stated as one month after the cut date, per the monthly cadence in
[STABILITY.md](STABILITY.md#cadence). Posting and closing the issues above is a release-owner
action. These notes are a draft and do not establish artifact availability.

## [5.1.0-alpha10] (2026-07-13)

**Bug Fixes**

* Fix multicaster race that leaves a new downstream without a producer [#740](https://github.com/MobileNativeFoundation/Store/pull/740)

## [5.1.0-alpha09] (2026-06-10)

**Bug Fixes**

* Fix RealMutableStore write-queue data race (inverted lock polarity) [#735](https://github.com/MobileNativeFoundation/Store/pull/735)

**Improvements**

* Fix CI checkout and coverage upload for fork PRs [#737](https://github.com/MobileNativeFoundation/Store/pull/737)

## [5.1.0-alpha08] (2026-01-11)

**Bug Fixes**

* Propagate converter exceptions instead of hanging indefinitely [#728](https://github.com/MobileNativeFoundation/Store/pull/728)
* Fix MutableStore.write() ignoring SourceOfTruth write failures [#727](https://github.com/MobileNativeFoundation/Store/pull/727)

## [5.1.0-alpha07] (2025-09-20)

* Remove Kotlinx Datetime dependency [#706](https://github.com/MobileNativeFoundation/Store/pull/706)

## [5.1.0-alpha06] (2025-02-27)

**New Features**

* Add WasmJS targeting capability [#646](https://github.com/MobileNativeFoundation/Store/pull/646)

**Bug Fixes**

* Fix eager conflict resolution deadlock in mutable Store operations [#679](https://github.com/MobileNativeFoundation/Store/pull/679)

**Improvements**

* Migrate test suite to Turbine testing library [#672](https://github.com/MobileNativeFoundation/Store/pull/672)
* Remove deprecated BroadcastChannel usage [#659](https://github.com/MobileNativeFoundation/Store/pull/659)
* Document extension functions in store.kt [#669](https://github.com/MobileNativeFoundation/Store/pull/669)
* Update README with links to store.mobilenativefoundation.org [#670](https://github.com/MobileNativeFoundation/Store/pull/670)
* Remove first paging iteration [#671](https://github.com/MobileNativeFoundation/Store/pull/671)
* Update Kover to 0.9.0-RC and consolidate CI workflows [#673](https://github.com/MobileNativeFoundation/Store/pull/673)

**Dependencies**

* Update Kermit to 2.0.5 [#683](https://github.com/MobileNativeFoundation/Store/pull/683)
* Update JaCoCo to 0.8.12 [#684](https://github.com/MobileNativeFoundation/Store/pull/684)
* Update Dokka to 1.9.20 [#687](https://github.com/MobileNativeFoundation/Store/pull/687)
* Update kotlinx-datetime to 0.6.2 [#688](https://github.com/MobileNativeFoundation/Store/pull/688)
* Configure Renovate for automated dependency management [#676](https://github.com/MobileNativeFoundation/Store/pull/676)

## [5.1.0-alpha05] (2025-10-18)

**Bug Fixes**

* Fix potential deadlock in RealMutableStore [#658](https://github.com/MobileNativeFoundation/Store/pull/658)
* Fix failing Node.js tests [#665](https://github.com/MobileNativeFoundation/Store/pull/665)
* Fix typo in RealStore.kt [#662](https://github.com/MobileNativeFoundation/Store/pull/662)

**Dependencies**

* Update Kermit to 2.0.4 [#655](https://github.com/MobileNativeFoundation/Store/pull/655)

## [5.1.0-alpha04] (2025-07-07)

**Important:** This release corrects the unintentional minimum SDK increase from alpha03.

**New Features**

* Add wasmJS target to Cache module [#605](https://github.com/MobileNativeFoundation/Store/pull/605)
* Add Cache.getAllPresent() [#609](https://github.com/MobileNativeFoundation/Store/pull/609)

**Improvements**

* Add binary compatibility validator and convention plugins [#645](https://github.com/MobileNativeFoundation/Store/pull/645)
* Lower JVM target to Java 11 [#648](https://github.com/MobileNativeFoundation/Store/pull/648)
* Use Java 11 everywhere [#649](https://github.com/MobileNativeFoundation/Store/pull/649)

**Bug Fixes**

* Fix failing CI tests [#641](https://github.com/MobileNativeFoundation/Store/pull/641)
* Update ChannelManager.kt [#637](https://github.com/MobileNativeFoundation/Store/pull/637)

## [5.1.0-alpha02] (2025-01-28)

* Release paging and core modules [#600](https://github.com/MobileNativeFoundation/Store/pull/600)

## [5.1.0-alpha01] (2025-01-26)

**New Features**

* Support Paging [#550](https://github.com/MobileNativeFoundation/Store/pull/550)
* Support custom error types [#583](https://github.com/MobileNativeFoundation/Store/pull/583)
* Add cacheOnly option to StoreReadRequest [#586](https://github.com/MobileNativeFoundation/Store/pull/586)
* Expose converter via StoreBuilder.from() function [#594](https://github.com/MobileNativeFoundation/Store/pull/594)

**Documentation**

* Update CONTRIBUTING.md [#589](https://github.com/MobileNativeFoundation/Store/pull/589)
* Update pull_request_template.md [#590](https://github.com/MobileNativeFoundation/Store/pull/590)

## [5.0.0] (2023-09-14 ) 
### Stable release of Store 5, major additions since Store 4 (no breaking changes)
* MutableStore
* Validator
* Fallback Mechanism
* KMP support
* Conflict Resolution for store writes
* Removal of experimental duration APIs
* StoreResult.NoNewData

## [5.0.0-beta03] (2023-08-11)

* Fix validator regression https://github.com/MobileNativeFoundation/Store/pull/573

## [5.0.0-beta02] (2023-07-21)

* Fix breaking changes with Source of
  Truth [#560](https://github.com/MobileNativeFoundation/Store/pull/560)

## [5.0.0-beta01] (2023-05-19)

* Delegate memory cache implementation and provide a hybrid cache with automatic list decomposition
  as a separate
  artifact [#548](https://github.com/MobileNativeFoundation/Store/pull/548)

## [5.0.0-alpha06] (2023-05-08)

* Separate MutableStoreBuilder from
  StoreBuilder [#542](https://github.com/MobileNativeFoundation/Store/commit/e050a15afc21c22ffea10a6a7d5f1b436ee34a6a)
* Support
  Rx2 [#531](https://github.com/MobileNativeFoundation/Store/commit/7d73f08cc07294d00b176325af792b51874dfeff)
* Introduce Fallback
  Mechanisms [#545](https://github.com/MobileNativeFoundation/Store/commit/d1e46a9d02703c798738bc5fb645344fefb90dd4)

## [5.0.0-alpha05] (2023-03-15)

* Target iOS Simulator
* Target Linux
* Make Bookkeeper optional

## [5.0.0-alpha04] (2023-02-24)

* Introduce MutableStore
* Implement RealMutableStore with Store delegate
* Extract Store and MutableStore methods to use cases

## [5.0.0-alpha03] (2022-12-18)

This release adds support for Store on iOS, JVM, and JS. Concepts and usage are unchanged from
Store4. In a future release we will reintroduce support for local and remote writes with conflict
resolution based on Google's offline first guidance.

* Target Android, iOS, JVM, JS
* Remove concept of Market
* Remove support for local and remote writes (temporary)

## [5.0.0-alpha02] (2022-12-04)

* Target iOS and JS
* Rename packages

## [5.0.0-alpha1] (2022-12-04)

* Introduce Market
* Support local and remote writes with conflict resolution based on Google's offline-first guidance
* Target Android and JVM

## [4.0.5] (2021-03-30)

* Update to Kotlin 1.6.10
    * Store `4.0.4-KT15` is the last version supporting Kotlin 1.5
    * Store `4.0.1` is the last version supporting Kotlin 1.4

## [4.0.4-KT15] (2021-12-08)

* Bug fixes and documentation updates

## [4.0.3-KT15] (2021-11-18)

* Update to Kotlin 1.5.31 and Coroutines 1.5.2
* Bug fixes and documentation updates

## [4.0.2-KT15] (2021-05-06)

**Kotlin 1.5 introduced breaking changes in the experimental Duration apis we used**
**4.0.2-KT15 is a duplicate of 4.0.1 but compiled for kotlin 1.5**
**Version 4.0.1 is the last version compatible with Kotlin 1.4**

* Fire off kotlin 1.5 compatible snapshot (#273)

## [4.0.1] (2021-05-06)

* Fix issues when upgrading to kotlin 1.5 (Deprecated duration api)
* Add piggyback to all stores

## [4.0.0] (2020-11-30)

* Update coroutines to 1.4.0, kotlin to 1.4.10 [#242](https://github.com/dropbox/Store/pull/242)

## [4.0.0-beta] (2020-09-21)

**API change**

* Remove need for generics with `Error` type (#220)

**Bug Fixes and Stability Improvements**

* Revert cache implementation to guava, rather than rolling our own (#200)
* Sample App improvements (#227)

## [4.0.0-alpha07] (2020-08-19)

**New Features**

* Add `StoreResult.NoNewData` to represent when a fetcher didn't return data. (#194)
* Move `Fetcher`-factories into `Companion` of `Fetcher` interface (#168)

**Bug Fixes and Stability Improvements**

* Fix a leak of non-global coroutine contexts. (#199)
* Update to Kotlin 1.4.0 and Coroutines 1.3.9 (#195)
* Update to Coroutines 1.3.5 and remove `@FlowPreview` and `@ExperimentalCoroutinesApi`
  annotations. (#166)

## [4.0.0-alpha06] (2020-04-29)

**Major API change!** (#123)

This release introduces a major change to `StoreBuilder`'s API. This should be the LAST major API
change to store before
we'll move to beta.

* The typealias `Fetcher` was added to standardize the input type for a `StoreBuilder`
* `SourceOfTruth` in now a top level interface and part of `Store`'s public API
* `StoreBuilder` can now only be created using a `Fetcher` and optionally a `SourceOfTruth`
* All the overloads for creating a `StoreBuilder` were moved to `Fetcher` and `SourceOfTruth` as
  appropriate.
* Rx artifacts were updated accordingly to match main artifacts.

## [4.0.0-alpha05] (2020-04-03)

**Bug Fixes and Stability Improvements**

* Contain @ExperimentalStdlibApi within relevant scope. (#154)
* Use AtomicFu to replace Java's AtomicBoolean and ReentrantLock (#147)
* migrate Multicast to Kotlin Test (#146)
* Remove Collections.unmodifiableMap (#145)
* Update AGP version (#143)
* Remove some unneeded java.util packages (#141)

## [4.0.0-alpha04] (2020-04-03)

**New Features**

* Add `asMap` function to Cache for backward compat (#136)
* Migrate filesystem library to use kotlin.time APIs (#133)
* Rx get fresh bindings (#130)
* Migrate cache library to use kotlin.time APIs (#129)
* Update sample app (#117)

**Bug Fixes and Stability Improvements**

* Use Kotlin version of ArrayDeque in ChannelManager (#134)
* Kotlin 1.3.70 and other dependencies updates (#125)
* Make SharedFlowProducer APIs safe (#121)
* Ensure network starts after disk is established (#115)
* Update to Gradle 6.2 (#111)

## [4.0.0-alpha03] (2020-02-13)

**New Features**

* Added Rx bindings, available as store-rx2 artifact (#93)
* Bug fixes (#90)
* Add ability to delete all entries in the store (#79)

## [4.0.0-alpha02] (2020-01-29)

**New Features**

* Introduce piggyback only downstreams to multicaster and fix #59 (#75)
* Change flow collection util to drain the flow (#64)
* Readme improvements (#70, #72)
* Avoid illegal cast in RealStore.stream (#69)
* Added docs to MemoryPolicy.setMemorySize (#67) (#68)

## [4.0.0-alpha01] (2020-01-08)

**New Features**

* Store has been rewritten using Kotlin Coroutines instead of RxJava

## [3.1.0] (2018-06-07)

**New Features**

* (#319) Store can now be used in Java (non-Android) projects
* (#338) Room integration for Store

**Bug Fixes and Stability Improvements**

* (#315) Add missing reading of expire-after-policy when creating a NoopPersister
* (#311) Update Kotlin & AGP versions
* (#328) Fix memory policy default size
* (#329) Adding docs to README for setting 1.8 compatibility
* (#273) Adds comments to the sample app
* (#336) Fixes errors in README

## [3.0.1] (2018-03-20)

**Bug Fixes and Stability Improvements**

* (#311) Update Kotlin & AGP versions
* (#314) Fix issues occured from RxJava1 dependency

## [3.0.0] (2018-02-01)

**New Features**

* (#275) Add ParsingFetcher that wraps Raw type Parser and Fetcher

**Bug Fixes and Stability Improvements**

* (#267) Kotlin 1.1.4 for store-kotlin
* (#290) Remove @Experimental from store-kotlin API
* (#283) Update build tools to 26.0.2
* (#259, #261, #272, #289, #303) README + documentation updates
* (#310) Sample app fixes

## [3.0.0-beta] (2017-07-26)

**New Features**

* (#229) Add store-kotlin module
* (#254) Add readAll / clearAll operations for a particular BarCode type
* (#250) Return object with meta data
* Create Code of Conduct

**Bug Fixes and Stability Improvements**

* (#239) Fix NoClassDefFoundError for StandardCharsets GsonBufferedSourceAdapter
* (#243) Update README for Rx2
* (#247) Remove intermediate streams
* (#246) Update to Moshi 1.5.0
* (#252) Fix stream for a single barcode

## [3.0.0-alpha] (2017-05-23)

This is a first alpha release of Store ported to RxJava 2.

**New Features**

* (#155) Port to RxJava 2
* (#220) Packages have been renamed to store3 to allow use of this artifact alongside the original
  Store
* (#185) Return Single/Maybe where appropriate
* (#189) Add lambdas to Store and Filesystem modules
* (#214) expireAfterAccess added to MemoryPolicy
* (#214) Deprecate setExpireAfter and getExpireAfter -- use new expireAfterWrite or
  expireAfterAccess, see #199 for
  MemoryPolicy changes
* (#214) Add Raw to BufferedSource transformer

**Bug Fixes and Stability Improvements**

* (#214) Fix networkBeforeStale on cold start with no connectivity
* (#214) Add a missing source.close() call
* (#164) FileSystemPersister.persisterIsStale() should return false if record is missing or policy
  is unspecified
* (#166) Remove apt dependency and use annotationProcessor instead
* (#214) Standardize store.stream() to emit only new items
* (#214) Fix typos
* (#214) Close source after write to filesystem

## [1.x]

* The change log for Store version 1.x can be
  found [here](https://github.com/NYTimes/Store/blob/develop/CHANGELOG.md).

[Unreleased]: https://github.com/MobileNativeFoundation/Store/compare/5.1.0-alpha10...HEAD

[5.1.0-alpha10]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.1.0-alpha10

[5.1.0-alpha09]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.1.0-alpha09

[5.1.0-alpha08]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.1.0-alpha08

[5.1.0-alpha07]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.1.0-alpha07

[5.1.0-alpha06]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.1.0-alpha06

[5.1.0-alpha05]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.1.0-alpha05

[5.1.0-alpha04]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.1.0-alpha04

[5.1.0-alpha02]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.1.0-alpha02

[5.1.0-alpha01]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.1.0-alpha01

[5.0.0-beta02]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.0.0-beta02

[5.0.0-beta01]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.0.0-beta01

[5.0.0-alpha06]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.0.0-alpha06

[5.0.0-alpha05]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.0.0-alpha05

[5.0.0-alpha04]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.0.0-alpha04

[5.0.0-alpha03]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.0.0-alpha03

[5.0.0-alpha02]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.0.0-alpha02

[5.0.0-alpha1]: https://github.com/MobileNativeFoundation/Store/releases/tag/5.0.0-alpha1

[4.0.5]: https://github.com/MobileNativeFoundation/Store/releases/tag/4.0.5

[4.0.4-KT15]: https://github.com/MobileNativeFoundation/Store/releases/tag/4.0.4-KT15

[4.0.3-KT15]: https://github.com/MobileNativeFoundation/Store/releases/tag/4.0.3-KT15

[4.0.2-KT15]: https://github.com/MobileNativeFoundation/Store/releases/tag/4.0.2-KT15

[4.0.1]: https://github.com/MobileNativeFoundation/Store/releases/tag/4.0.1

[4.0.0]: https://github.com/MobileNativeFoundation/Store/releases/tag/4.0.0

[4.0.0-beta]: https://github.com/MobileNativeFoundation/Store/releases/tag/4.0.0-beta

[4.0.0-alpha07]: https://github.com/MobileNativeFoundation/Store/releases/tag/4.0.0-alpha07

[4.0.0-alpha06]: https://github.com/MobileNativeFoundation/Store/releases/tag/4.0.0-alpha06

[4.0.0-alpha05]: https://github.com/MobileNativeFoundation/Store/releases/tag/4.0.0-alpha05

[4.0.0-alpha04]: https://github.com/MobileNativeFoundation/Store/releases/tag/4.0.0-alpha04

[4.0.0-alpha03]: https://github.com/MobileNativeFoundation/Store/releases/tag/4.0.0-alpha03

[4.0.0-alpha02]: https://github.com/MobileNativeFoundation/Store/releases/tag/4.0.0-alpha02

[4.0.0-alpha01]: https://github.com/MobileNativeFoundation/Store/releases/tag/4.0.0-alpha01

[3.1.0]: https://github.com/MobileNativeFoundation/Store/releases/tag/3.1.0

[3.0.1]: https://github.com/MobileNativeFoundation/Store/releases/tag/3.0.1

[3.0.0]: https://github.com/MobileNativeFoundation/Store/releases/tag/3.0.0

[3.0.0-beta]: https://github.com/MobileNativeFoundation/Store/releases/tag/3.0.0-beta

[3.0.0-alpha]: https://github.com/MobileNativeFoundation/Store/releases/tag/3.0.0-alpha

[1.x]: https://github.com/NYTimes/Store/blob/develop/CHANGELOG.md
