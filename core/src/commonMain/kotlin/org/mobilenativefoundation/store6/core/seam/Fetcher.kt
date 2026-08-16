package org.mobilenativefoundation.store6.core.seam

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey

/**
 * Retrieves remote values for a [StoreKey] without mutating Store state.
 *
 * Implementations must be cooperative with coroutine cancellation and must not write Store
 * residence, persistence, or bookkeeping directly. The engine supplies a non-null `etag` if and
 * only if it selected [FetchPlan.Conditional]; return [FetcherResult.NotModified] to confirm that
 * the resident value is still current.
 *
 * @param K the key type accepted by the fetcher
 * @param V the non-null value type produced by the fetcher
 */
@ExperimentalStoreApi
@SubclassOptInRequired(DelicateStoreApi::class)
public interface Fetcher<K : StoreKey, V : Any> {
    /** Retrieves [key], conditionally against [etag] when one is supplied by the engine. */
    // docs:snippet:guides-fetchers-seam-signature
    public suspend fun fetch(
        key: K,
        etag: String?,
    ): FetcherResult<V>
    // docs:snippet:end
}
