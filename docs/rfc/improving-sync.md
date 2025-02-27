# Store RFC: Improving Sync

## Metadata

- **Authors**: [Matt Ramotar](https://github.com/matt-ramotar)
- **Reviewers**: _TBD_
- **Started**: February 22, 2025

## Introduction

Store is our solution for working with data in Kotlin. It orchestrates CRUD operations with offline-first capabilities. It simplifies data flow between local and remote sources, ensuring that applications can remain responsive even under unreliable network conditions.

Store5 specifically introduced the `MutableStore` API to unify local and remote data synchronization. It provides mechanisms for create, update, and delete operations. However, we’ve identified critical gaps around resilience (automated retry, robust background sync) and deletion handling (both local and server-initiated removals). Along with these, we also need customizable conflict resolution strategies to better address real-world complexities.

The overarching goal of this RFC is to enhance Store5’s synchronization features without introducing breaking changes. In particular:

1. **Synchronization Resilience**: Automated retries and background scheduling using the `Meeseeks` library (a KMP task scheduling library influenced by [WorkManager](https://developer.android.com/reference/androidx/work/WorkManager) and [BGTaskScheduler](https://developer.apple.com/documentation/backgroundtasks/bgtaskscheduler)).
2. **Deletion Handling**: An integrated approach that ensures consistency when items are removed, whether locally, offline, or via server-side signals.
3. **Conflict Resolution Policies**: Extensible mechanisms to handle collisions gracefully without forcing a "one size fits" approach. This will include a default `last-write-wins` and `merge-based` approach, but will introduce hooks for users to define their own policies.

This RFC is intended for Store contributors and developers using Store. If you’re new to the library, please see:

- [Why Store?](https://store.mobilenativefoundation.org/docs/intro)
- [Quickstart](http://Quickstart)
- [Store5 Concepts](https://store.mobilenativefoundation.org/docs/concepts/store5/overview)

## Background

### Store5 Architecture and Sync Components

1. **Store and MutableStore**

   A [Store](https://store.mobilenativefoundation.org/docs/concepts/store5/store) streams data (via Kotlin `Flow`) from a remote source (via a [Fetcher](https://store.mobilenativefoundation.org/docs/concepts/store5/fetcher)) and a local source (the [SourceOfTruth](https://store.mobilenativefoundation.org/docs/concepts/store5/source-of-truth)). [MutableStore](https://store.mobilenativefoundation.org/docs/concepts/store5/mutable-store) extends this by adding support for CRUD operations, enabling two-way sync between local and remote.

2. **SourceOfTruth (SOT)**

   This is the canonical local persistence layer (e.g., [SQLDelight](https://github.com/sqldelight/sqldelight), [Room](https://developer.android.com/jetpack/androidx/releases/room)). Developers can configure read, write, and delete operations. For instance, one might define `delete = { key -> dao.delete(key) }` during SOT setup.

3. **Fetcher**

   Orchestrates fetching from the server. It typically returns data or throws errors. As part of this RFD, we will formalize how a fetcher can indicate a server-initiated deletion (`FetcherResult.Deleted`, a specialized [FetcherResult](https://github.com/MobileNativeFoundation/Store/blob/ed7c31b794acc1371745f594eb5d5080f1b3dc4a/store/src/commonMain/kotlin/org/mobilenativefoundation/store/store5/FetcherResult.kt) as discussed below).

4. **Updater**

   Introduced in Store5, an [Updater](https://store.mobilenativefoundation.org/docs/concepts/store5/updater) pushes local changes to the remote source. This is crucial for offline use cases: if a user modifies or deletes data offline, the `Updater` ensures these changes eventually make it to the server once connectivity is restored.

5. **Bookkeeper**

   Another Store5 addition. It tracks local changes that have not yet been successfully synced. Whenever a network call fails, [Bookkeeper](https://store.mobilenativefoundation.org/docs/concepts/store5/bookkeeper) logs the failure so the system can handle it later. Think of it as a ledger that ensures no local changes “fall through the cracks,” including deletions.

6. **Memory Cache**

   In addition to SOT, Store5 typically keeps recently fetched data in memory for quick reads. Deletions must also invalidate this cache to avoid showing stale items.

### Current State of Sync

Store5’s [Bookkeeper](https://store.mobilenativefoundation.org/docs/concepts/store5/bookkeeper) does track failed sync attempts by timestamp, but automatic re-sync is only triggered upon subsequent reads for that key. If a key is written locally and never fetched, it can remain unsynced. The biggest pain point is that there’s no built-in background mechanism to ensure outstanding changes get retried proactively.

> “Values that are written could potentially remain in Store forever if they’re never requested again.”
>
> — [#677](https://github.com/MobileNativeFoundation/Store/issues/677)

### Current State of Deletion Support

Prior Store versions (e.g., [Store4](https://youtu.be/raWdIwsDe-g)) introduced fundamental deletion methods (e.g., `clearAll`). However, we still do not have a cohesive end-to-end approach to server-initiated deletions. Without this, local caches may display items that the server already removed. Additionally, we don't have sufficient documentation on how to integrate local or server-side deletions with `Bookkeeper` and `Updater`.

> "One challenge I’m facing is handling deleted data on the server. Since the server doesn’t return deleted items, Store5 doesn’t automatically detect them, which leaves stale records on the client."
>
> — [#685](https://github.com/MobileNativeFoundation/Store/discussions/685)

### Recap

We have the building blocks for robust sync ([MutableStore](https://store.mobilenativefoundation.org/docs/concepts/store5/mutable-store), [Bookkeeper](https://store.mobilenativefoundation.org/docs/concepts/store5/bookkeeper), [Updater](https://store.mobilenativefoundation.org/docs/concepts/store5/updater), [SourceOfTruth](https://store.mobilenativefoundation.org/docs/concepts/store5/source-of-truth)). But we need to tighten up deletion support, add background sync scheduling, and clarify conflict resolution flows.

## Considerations

1. **Offline Conflict (Update vs. Delete)**

   When an item is deleted server-side while a user edits it offline, the sync conflict can cause user changes to be invalidated. A typical approach is to treat server deletions as final, though more complex merges or user prompts are possible. Our improvements aim to make it easier for developers to define their own resolution strategies.

2. **Concurrency and Ordering Guarantees**

   Multiple threads, coroutines, or tasks might perform operations on overlapping sets of data at the same time. Store operations are already serialized with a combination of Store-level `Mutex` (to safely mutate shared structures) and a per-key `ThreadSafety` lock (to ensure atomic writes and read consistency on a single key). This design prevents race conditions and preserves sequential consistency across parallel reads and writes.

3. **Sequential Consistency**

   Even if operations aren't truly concurrent, we still need a guarantee that the system view of operations respects the actual order they were issued in. Need to ensure that once an operation completes, every subsequent operation or read sees the effects of that prior operation, rather than some out-of-order or stale state.

4. **Re-Fetch of Deleted Data**

   After an item is removed, calling `store.get(key)` triggers a fetch. If the server returns 404, the store knows it’s gone. This can be slightly redundant, but we plan to keep the core library simple and let “tombstone caching” be an opt-in feature under StoreX (discussed below).

5. **Backward Compatibility**

   Not willing to break existing usage. Not all developers need advanced sync.

## Core Plan

1. **[MobileNativeFoundation/Store](https://github.com/MobileNativeFoundation/Store)**: Core library. The primitive, building block for working with data.
2. **MobileNativeFoundation/StoreX**: Extension library. Layers advanced features and integrates popular patterns. Introduced at Droidcon ([Modern Paging at Scale: StoreX + Compose](https://www.droidcon.com/2024/10/17/modern-paging-at-scale-storex-compose/)).

### Store-Level

1. **Standardized Fetcher Deletion Signal**

   Introducing a `FetcherResult.Deleted` type as a structured way for a [Fetcher](https://store.mobilenativefoundation.org/docs/concepts/store5/fetcher) to indicate deletion. When `Fetcher` returns `FetcherResult.Deleted`, `Store` will automatically invoke the local SOT's `delete(key)` and invalidate the in-memory caches for that key.

   ```kotlin
   sealed class FetcherResult<out Network : Any> {
      data object Deleted: FetcherResult<Nothing>()
   }
   ```

   There are two primary reasons for deciding to use a specialized `FetcherResult` rather than introducing `NotFoundException` handling or returning `null`: The first is that a specialized `FetcherResult` enables distinguishing an item "no longer exists" rather than just "does not exist" (which could also mean "has never existed"). The second reason is that existing users of Store could be using `NotFoundException` to handle other cases, and we don't want to break them.

2. **Conflict Resolution Hooks**

   Fundamental to ensuring simultaneous changes across devices or clients do not result in data corruption. Will address this by introducing customizable policies such as `last-write-wins`, `merge-based` resolutions, and hooks to enable user-defined strategies. This will give developers fine-grained control over how conflicts are handled.

   ```kotlin
   interface ConflictResolutionPolicy<Key, Input, Output> {
    suspend fun resolve(
        key: Key,
        incoming: Input,
        current: Output?
    ): Output
   }
   ```

   ```kotlin
   mutableStoreBuilder
      .withConflictResolutionPolicy(StoreConflictResolutionPolicy.LastWriteWins)
      .build()
   ```

3. **Failure Recovery Mechanism for Periodically Syncing in Background**

   Instead of waiting for the user to re-fetch, we should introduce a background sync approach (see `Meeseeks` below). This will ensure items stuck in `Bookkeeper` eventually get retried.

4. **Mechanism for Handling Server Push Events**

   If a server can push “item X was deleted,” Store should be able to handle that proactively (clear local data or mark it as removed). This will be an optional component provided to the [StoreBuilder](https://github.com/MobileNativeFoundation/Store/blob/fd2ffbd14f740d1892a88fbb8bd3abfec128ae8b/store/src/commonMain/kotlin/org/mobilenativefoundation/store/store5/StoreBuilder.kt).

5. **Mechanism for Handling Bulk Updates**

   More of a performance optimization that we should consider. For example, `deleteMany(keys: List<Key>)` and `updateMany(updates: List<Pair<Key, Item>>)`. This ensures consistency and efficiency. The RFC that covers this will need to thoughtfully address partial failure handling.

### StoreX-Level

1. **Meeseeks**

   Named after the helpful beings from the [Mr. Meeseeks Box](https://rickandmorty.fandom.com/wiki/Mr._Meeseeks_Box) that exist solely to complete a single task, reliably handle it, work in parallel when needed, and disappear as soon as it is complete. `Meeseeks` is a KMP library influenced by the iOS [BGTaskScheduler](https://developer.apple.com/documentation/backgroundtasks/bgtaskscheduler) and Android [WorkManager](https://developer.android.com/reference/androidx/work/WorkManager). This library will be used by Store to coordinate with `Bookkeeper` to automatically retry unsynced changes. Bookkeeper's ledger will be periodically retried via `Meeseeks`. Retry strategies will be configurable. For instance, a common approach in mobile apps is “last write wins,” where the most recent change overwrites previous ones. But some apps might merge changes "field by field" or prefer the server’s version as the source of truth. This flexibility will enable our users to adapt sync behavior based on their requirements.

2. **Tombstone Handling**

   We can also maintain a table or map of deleted keys so we don’t re-request them. This will help prevent redundant fetches for items the server has indicated are definitively gone.

### Intertwined

These solutions are designed to work together. They reinforce each other to create a more robust synchronization flow, which is the backbone of a reliable data layer. It ensures consistency between local and remote data sources while handling the unpredictable nature of network conditions, conflicts, and deletions.

- **Resilience Through Automated Sync Retries**: At the core of this improved synchronization model is resilience, powered by `Meeseeks`. Sync failures, whether due to transient network issues or server unavailability, will no longer be dead ends. Instead, Store will intelligently schedule and retry failed sync operations in the background, ensuring eventual consistency without requiring explicit user intervention. By leveraging a task-scheduling library designed for KMP, this approach will enable Store5 to support automatic retries across mobile, web, desktop, and server environments.

- **Comprehensive Deletion Handling**: Robust deletion handling ensures Store5 always reflects the latest state of the data by propagating removals consistently across memory caches, local databases, and remote sources. Prior to this RFC, deletions could introduce inconsistencies, leading to stale or misleading data in offline scenarios. Our sync mechanism will have a structured approach to deletions. `Store` will be updated to interpret specialized `FetcherResult.Deleted` as deletion signals. The responsibility of mapping network responses remains deferred to the developer.

## Detailed Designs

Each of the solutions outlined above will have its own RFC. If you're interested in contributing to any of these, please reach out:

- [Kotlin](https://kotlinlang.slack.com/archives/D04CUNMT6CD)
- [ASG](https://androidstudygroup.slack.com/archives/D041CJK9NH2)

## Milestones and Target Dates

| Milestone                                                  | Enhancements                                    | Release | Target Date    |
| ---------------------------------------------------------- | ----------------------------------------------- | ------- | -------------- |
| 1. Support for Server-Initiated Deletions via `Fetcher`    | Standardized Fetcher Signal, Conflict Policies  | 5.2.0   | March 21, 2025 |
| 2. Support for Automatic Retries and Background Scheduling | Meeseeks, Meeseeks Integration for Retry        | 5.3.0   | April 18, 2025 |
| 3. Advanced Sync Capabilities                              | Tombstone Caching, Push Events, Bulk Operations | 5.4.0   | May 16, 2025   |