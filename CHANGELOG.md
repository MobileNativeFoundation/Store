Change Log
==========


The change log for Store version 1.x can be found [here](https://github.com/NYTimes/Store/blob/develop/CHANGELOG.md).

Version 4.0.6 (2022-11-20)
----------------------------
* Flip coordinates from `com.dropbox.mobile.store` to `org.mobilenativefoundation.store`

Version 4.0.5 (2021-03-30)
----------------------------
* Update to Kotlin 1.6.10
  * Store `4.0.4-KT15` is the last version supporting Kotlin 1.5
  * Store `4.0.1` is the last version supporting Kotlin 1.4

Version 4.0.4-KT15 *(2021-12-08)
----------------------------
** Bug fixes and documentation updates

Version 4.0.3-KT15 *(2021-11-18)
----------------------------
** Update to Kotlin 1.5.31 and Coroutines 1.5.2
** Bug fixes and documentation updates

Version 4.0.2-KT15 *(2021-05-06)
----------------------------
**Kotlin 1.5 introduced breaking changes in the experimental Duration apis we used**
**4.0.2-KT15 is a duplicate of 4.0.1 but compiled for kotlin 1.5**
**Version 4.0.1 is the last version compatible with Kotlin 1.4**

* Fire off kotlin 1.5 compatible snapshot (#273)


Version 4.0.1 *(2021-05-06)*
----------------------------
* Fixes issues when upgrading to kotlin 1.5 (Deprecated duration api)
* Add piggyback to all stores

Version 4.0.0 *(2020-11-30)*
----------------------------
* update coroutines to 1.4.0, kotlin to 1.4.10 [#242](https://github.com/dropbox/Store/pull/242)


Version 4.0.0-beta *(2020-09-21)*
----------------------------
**API change**
* Remove need for generics with `Error` type (#220)

**Bug Fixes and Stability Improvements**
* Revert cache implementation to guava, rather than rolling our own (#200)
* Sample App improvements (#227)

Version 4.0.0-alpha07 *(2020-08-19)*
----------------------------
**New Features**
* Add `StoreResult.NoNewData` to represent when a fetcher didn't return data. (#194)
* Move `Fetcher`-factories into `Companion` of `Fetcher` interface (#168)

**Bug Fixes and Stability Improvements**
* Fix a leak of non-global coroutine contexts. (#199)
* Update to Kotlin 1.4.0 and Coroutines 1.3.9 (#195)
* Update to Coroutines 1.3.5 and remove `@FlowPreview` and `@ExperimentalCoroutinesApi` annotations. (#166)

Version 4.0.0-alpha06 *(2020-04-29)*
----------------------------
**Major API change!** (#123)

This release introduces a major change to `StoreBuilder`'s API. This should be the LAST major API change to store before we'll move to beta.
* The typealias `Fetcher` was added to standardize the input type for a `StoreBuilder`
* `SourceOfTruth` in now a top level interface and part of `Store`'s public API
* `StoreBuilder` can now only be created using a `Fetcher` and optionally a `SourceOfTruth`
* All the overloads for creating a `StoreBuilder` were moved to `Fetcher` and `SourceOfTruth` as appropriate.
* Rx artifacts were updated accordingly to match main artifacts.

Version 4.0.0-alpha05 *(2020-04-03)*
----------------------------

**Bug Fixes and Stability Improvements**
* Contain @ExperimentalStdlibApi within relevant scope. (#154)
* Use AtomicFu to replace Java's AtomicBoolean and ReentrantLock (#147)
* migrate Multicast to Kotlin Test (#146)
* Remove Collections.unmodifiableMap (#145)
* Update AGP version (#143)
* Remove some unneeded java.util packages (#141)

Version 4.0.0-alpha04 *(2020-04-03)*
----------------------------

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

Version 4.0.0-alpha03 *(2020-02-13)*
----------------------------

**New Features**
* Added Rx bindings, available as store-rx2 artifact (#93)
* Bug fixes (#90)
* Add ability to delete all entries in the store (#79)

Version 4.0.0-alpha02 *(2020-01-29)*
----------------------------

**New Features**
* Introduce piggyback only downstreams to multicaster and fix #59 (#75)
* Change flow collection util to drain the flow (#64)
* Readme improvements (#70, #72)
* Avoid illegal cast in RealStore.stream (#69)
* Added docs to MemoryPolicy.setMemorySize (#67) (#68)

Version 4.0.0-alpha01 *(2020-01-08)*
----------------------------

**New Features**
* Store has been rewritten using Kotlin Coroutines instead of RxJava

Version 3.1.0 *(2018-06-07)*
----------------------------

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

Version 3.0.1 *(2018-03-20)*
----------------------------

**Bug Fixes and Stability Improvements**

* (#311) Update Kotlin & AGP versions
* (#314) Fix issues occured from RxJava1 dependency

Version 3.0.0 *(2018-02-01)*
----------------------------

**New Features**

* (#275) Add ParsingFetcher that wraps Raw type Parser and Fetcher

**Bug Fixes and Stability Improvements**

* (#267) Kotlin 1.1.4 for store-kotlin 
* (#290) Remove @Experimental from store-kotlin API
* (#283) Update build tools to 26.0.2
* (#259, #261, #272, #289, #303) README + documentation updates
* (#310) Sample app fixes

Version 3.0.0-beta *(2017-07-26)*
----------------------------

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

Version 3.0.0-alpha *(2017-05-23)*
----------------------------

This is a first alpha release of Store ported to RxJava 2.

**New Features**

* (#155) Port to RxJava 2
* (#220) Packages have been renamed to store3 to allow use of this artifact alongside the original Store
* (#185) Return Single/Maybe where appropriate
* (#189) Add lambdas to Store and Filesystem modules
* (#214) expireAfterAccess added to MemoryPolicy
* (#214) Deprecate setExpireAfter and getExpireAfter -- use new expireAfterWrite or expireAfterAccess, see #199 for 
MemoryPolicy changes
* (#214) Add Raw to BufferedSource transformer


**Bug Fixes and Stability Improvements**

* (#214) Fix networkBeforeStale on cold start with no connectivity
* (#214) Add a missing source.close() call
* (#164) FileSystemPersister.persisterIsStale() should return false if record is missing or policy is unspecified
* (#166) Remove apt dependency and use annotationProcessor instead
* (#214) Standardize store.stream() to emit only new items
* (#214) Fix typos
* (#214) Close source after write to filesystem