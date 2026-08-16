package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.flow.Flow

/**
 * Provides asynchronous access to non-null values identified by [StoreKey] instances.
 *
 * Create stores through the [store] DSL. Implementing this interface directly is a delicate
 * operation: implementations must uphold every documented semantic, including the one-failure-
 * channel rule ([stream] emits and never throws; [get] throws and never emits) and the
 * completion postconditions of the maintenance operations.
 *
 * @param K the key type accepted by this store
 * @param V the non-null value type produced by this store
 */
@SubclassOptInRequired(DelicateStoreApi::class)
public interface Store<K : StoreKey, out V : Any> {
    /**
     * Observes retrieval state and values for [key].
     *
     * Fetcher and source-of-truth failures encountered while retrieving [key] are emitted as
     * [StoreResult.Error] values rather than thrown to the collector. A
     * [Freshness.MustBeFresh] initial-cycle fetch or revalidation failure emits one error and
     * completes the flow; every other failure leaves the flow live. Otherwise, the flow remains
     * active until its collector is cancelled or the store is closed, and continues to report
     * later values, including refetches triggered by invalidation and the absent-value transition
     * after a clear. Concurrent collectors and callers for one key share a single fetch.
     *
     * [Freshness.CachedOrFetch] serves a resident value and refreshes it after invalidation;
     * [Freshness.MaxAge] withholds an invalidated or over-age resident until a fetch succeeds;
     * [Freshness.MustBeFresh] always withholds residence and treats an initial-cycle failure as
     * terminal; [Freshness.StaleIfError] serves invalidated residence while reporting a failed
     * refresh; and [Freshness.LocalOnly] never fetches.
     *
     * @param key the key to observe
     * @param freshness the freshness policy applied to this observation
     * @return a flow of loading, data, revalidation, and error results for the key
     * @throws IllegalStateException if the store is closed before this call or before collection
     * begins
     */
    public fun stream(
        key: K,
        freshness: Freshness = Freshness.CachedOrFetch,
    ): Flow<StoreResult<V>>

    /**
     * Returns the value for [key] according to [freshness].
     *
     * [Freshness.CachedOrFetch] returns residence immediately and refreshes it in the background
     * after invalidation. [Freshness.MaxAge] and [Freshness.MustBeFresh] block for a qualifying
     * fetch. [Freshness.StaleIfError] blocks after invalidation and returns the resident value only
     * when the refresh fails. [Freshness.LocalOnly] never fetches.
     *
     * This read is never projected by a configured overlay; overlays apply only to [stream].
     *
     * @param key the key whose value is requested
     * @param freshness the freshness policy applied to this read
     * @return the resolved value
     * @throws StoreException when no value can be returned because fetching or source-of-truth
     * access failed, a concurrent [clear] removed the key while its fetch was in flight,
     * [Freshness.LocalOnly] found no local value, or the server reported deletion
     * ([StoreError.Missing])
     * @throws IllegalStateException if the store is already closed
     */
    public suspend fun get(
        key: K,
        freshness: Freshness = Freshness.CachedOrFetch,
    ): V

    /**
     * Marks the value for [key] stale without removing it.
     *
     * On return, active streams of [key] have been signaled and will observe refetched data;
     * the resident value is kept and served as stale in the meantime. Invalidation is
     * level-triggered monotone state, so a signal issued during any race window is never lost.
     *
     * The stale mark is durable and survives process restart until a later successful fetch or
     * revalidation clears it.
     *
     * @param key the key to invalidate
     * @throws StoreException if persisting the stale mark fails; resident state is not signaled
     * @throws IllegalStateException if the store is already closed
     */
    public suspend fun invalidate(key: K)

    /**
     * Marks every key in [namespace] stale without removing values.
     *
     * The durable namespace watermark covers keys whether or not they are currently resident.
     * Resident streams are signaled on return unless a later successful fetch already superseded
     * the watermark.
     *
     * @param namespace the namespace to invalidate
     * @throws StoreException if persisting the namespace watermark fails; resident state is not
     * signaled
     * @throws IllegalStateException if the store is already closed
     */
    public suspend fun invalidateNamespace(namespace: StoreNamespace)

    /**
     * Marks every key in this store stale without removing values.
     *
     * The durable global watermark covers every namespace, including keys that are not currently
     * resident. Resident streams are signaled on return unless a later successful fetch already
     * superseded the watermark.
     *
     * @throws StoreException if persisting the global watermark fails; resident state is not
     * signaled
     * @throws IllegalStateException if the store is already closed
     */
    public suspend fun invalidateAll()

    /**
     * Destructively removes the value for [key].
     *
     * On return, the resident value is gone: active streams observe the absent-value transition
     * ([StoreResult.Loading]) and then refetched data, and an in-flight fetch that started
     * before the clear can no longer commit — its waiters observe [StoreError.Missing].
     *
     * Removal includes the configured source-of-truth row and its freshness bookkeeping for
     * [key].
     *
     * @param key the key to clear
     * @throws StoreException if deleting the configured source-of-truth row fails (engine state
     * remains unchanged), or if freshness cleanup fails after the row and resident state were
     * removed
     * @throws IllegalStateException if the store is already closed
     */
    public suspend fun clear(key: K)

    /**
     * Destructively removes every value in [namespace].
     *
     * A scoped fence drains affected commit atoms before a resident supersede sweep, blocks new
     * affected commits through the source-of-truth delete and bookkeeping cleanup, then performs
     * a purge sweep. A fetcher call may finish while fenced, but its commit waits; tickets present
     * before purge are superseded. While the call is in flight, an already-running read may briefly
     * observe the old row and an active collector may receive one queued duplicate old [StoreResult.Data].
     * The purge closes the registry-snapshot and rehydration window before normal return. Later
     * demand and writes made outside this Store remain later authority.
     *
     * @param namespace the namespace to clear
     * @throws StoreException if durable deletion or bookkeeping cleanup fails; completed earlier
     * steps remain applied and conservative
     * @throws IllegalStateException if the store is already closed
     */
    public suspend fun clearNamespace(namespace: StoreNamespace)

    /**
     * Destructively removes every value in this store.
     *
     * A global fence drains every commit atom before a resident supersede sweep, blocks new commits
     * through the source-of-truth delete and bookkeeping cleanup, then performs a purge sweep. A
     * fetcher call may finish while fenced, but its commit waits; tickets present before purge are
     * superseded. While the call is in flight, an already-running read may briefly observe the old
     * row and an active collector may receive one queued duplicate old [StoreResult.Data]. The purge
     * closes the registry-snapshot and rehydration window before normal return. Later demand and
     * writes made outside this Store remain later authority.
     *
     * @throws StoreException if durable deletion or bookkeeping cleanup fails; completed earlier
     * steps remain applied and conservative
     * @throws IllegalStateException if the store is already closed
     */
    public suspend fun clearAll()

    /**
     * Releases resources owned by this store and cancels its in-flight work.
     *
     * Collectors and value requests waiting on in-flight work are cancelled. Subsequent calls
     * to any operation fail with [IllegalStateException] and the message `Store is closed.`
     * Calling `close()` more than once has no additional effect.
     */
    public fun close()
}
