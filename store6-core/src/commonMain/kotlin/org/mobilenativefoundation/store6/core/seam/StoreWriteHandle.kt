package org.mobilenativefoundation.store6.core.seam

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey

/**
 * Engine-backed acknowledgement path for committing and refreshing source-of-truth values.
 *
 * @param K the key type accepted by the owning Store
 * @param V the non-null value type committed by the owning Store
 */
@ExperimentalStoreApi
@SubclassOptInRequired(DelicateStoreApi::class)
public interface StoreWriteHandle<K : StoreKey, V : Any> {
    /**
     * Commits [value] to the source of truth for [key] under the engine write lock.
     *
     * This acknowledgement path publishes synthetic-ticket attribution from committing through
     * committed so streams observe `Data(origin = SOT)`. It never fetches or calls the network and
     * does not record bookkeeping success; callers pair it with [confirmFresh] when the value is
     * known fresh.
     *
     * Cancellation propagates after the synthetic owner state is terminalized. Other persistence
     * failures throw [StoreException] carrying [StoreError.Persistence], retain the original cause,
     * and leave engine state safe and unchanged.
     */
    public suspend fun apply(
        key: K,
        value: V,
    )

    /**
     * Marks [key] durably stale with semantics identical to `Store.invalidate(key)`.
     *
     * Active streams are signaled to refetch, and the engine produces both
     * [KeyEvents.Invalidated] and the configured invalidation telemetry callback.
     */
    public suspend fun markStale(key: K)

    /**
     * Confirms the resident value for [key] as fresh without fetching.
     *
     * When residence exists, this records bookkeeping success, clears durable staleness like a
     * `304 Not Modified`, and refreshes resident metadata and its commit epoch. Active streams may
     * observe one data re-emission with refreshed flags. With no resident value this does nothing.
     * This call alone is not an observation mechanism.
     */
    public suspend fun confirmFresh(
        key: K,
        etag: String?,
    )
}
