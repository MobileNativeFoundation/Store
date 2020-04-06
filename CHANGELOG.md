Change Log
==========

The change log for Store version 1.x can be found [here](https://github.com/NYTimes/Store/blob/develop/CHANGELOG.md).

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