package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi

/**
 * Persists each write locally before admitting it for server synchronization. Pending writes for
 * one key may be coalesced: posting the latest admitted value can complete earlier writes in the
 * same batch. Updater calls are serialized per key within this instance, while newer writes can
 * persist locally during an earlier updater call.
 *
 * Success callbacks run after internal synchronization locks are released and may reenter the
 * store. Participating storage, updater, and bookkeeping adapters must not recursively read,
 * write, or clear the same store and key. This restriction follows inherited coroutine context;
 * detached work that discards that context cannot be detected.
 *
 * Admitted pending writes remain in this instance's memory until acknowledged, even if their
 * caller is cancelled. This state is not a durable outbox and does not guarantee recovery
 * across process death. A Bookkeeper is required for eager synchronization during reads.
 */
@ExperimentalStoreApi
interface MutableStore<Key : Any, Output : Any> :
    Read.StreamWithConflictResolution<Key, Output>,
    Write<Key, Output>,
    Write.Stream<Key, Output>,
    Clear.Key<Key>,
    Clear
