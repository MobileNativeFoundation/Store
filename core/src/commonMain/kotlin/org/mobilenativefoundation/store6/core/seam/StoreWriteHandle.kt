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
     * does not record bookkeeping success. Use [applyAcknowledgement] to commit an acknowledged
     * value together with its metadata.
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
     * Captures opaque freshness evidence before sending a request that can acknowledge [key].
     *
     * The engine returns evidence for its current key residency. The default returns `null`,
     * which supplies no freshness authority. Capture new evidence only for a new logical request.
     * Retransmissions of the same logical request and adoption retries retain the original evidence.
     */
    public suspend fun captureFreshness(key: K): FreshnessEvidence? = null

    /**
     * Adopts [value] and its [etag] under one engine commit fence.
     *
     * Current [freshnessEvidence] permits recording success and clearing earlier stale marks.
     * Missing or expired evidence first marks the key durably stale, then writes the value without
     * recording success. A failed stale mark aborts before writing. If marking succeeds and the
     * write fails, the previous value remains durably stale. Ordinary persistence failures throw
     * [StoreException] with [StoreError.Persistence]; cancellation remains transparent.
     *
     * The engine attaches [adoption] only to observations proven to include this source commit.
     * The default implementation conservatively marks stale and calls [apply]; it never confirms
     * freshness or supplies adoption observation identity. Implementations that supply these
     * stronger guarantees must override this method.
     */
    public suspend fun applyAcknowledgement(
        key: K,
        value: V,
        etag: String?,
        freshnessEvidence: FreshnessEvidence? = null,
        adoption: SourceAdoption? = null,
    ) {
        markStale(key)
        apply(key, value)
    }

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
     * This call alone is not an observation mechanism. It does not bind freshness to an earlier
     * writer or request. Acknowledgement callers use [applyAcknowledgement] with evidence captured
     * before sending their request.
     */
    public suspend fun confirmFresh(
        key: K,
        etag: String?,
    )
}
