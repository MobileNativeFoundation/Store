package org.mobilenativefoundation.store6.realtime

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

/**
 * A server-authored fact for one Store identity or watermark.
 *
 * The application owns transport (WebSocket, SSE, or any other inbound stream) and maps each
 * received frame onto one of these variants. [RealtimeBinding] then performs the corresponding
 * Store operation. This type is not a wire format.
 *
 * @param K the key type accepted by the bound Store
 * @param V the non-null value type committed by an [Upsert]
 */
@ExperimentalStoreApi
public sealed interface RealtimeMessage<out K : StoreKey, out V : Any> {
    /**
     * A present value that should become confirmed source-of-truth residence for [key].
     *
     * An adopting [RealtimeBinding] commits [value] through `StoreWriteHandle.apply` and then
     * `confirmFresh` with [etag]. That pair never fetches. [etag] is written as the new resident
     * tag, including `null`. An invalidating binding has no write handle and marks [key] stale
     * through `Store.invalidate` instead, so the next qualifying read refetches.
     */
    @ExperimentalStoreApi
    public class Upsert<out K : StoreKey, out V : Any>(
        public val key: K,
        public val value: V,
        public val etag: String?,
    ) : RealtimeMessage<K, V>

    /**
     * Confirmation that the resident value for [key] is still current, analogous to a
     * `304 Not Modified`.
     *
     * An adopting [RealtimeBinding] calls `StoreWriteHandle.confirmFresh` with [etag] and does
     * not fetch. With no resident value that call is a no-op. [etag] is written as the new
     * resident tag, including `null`. An invalidating binding ignores this variant.
     */
    @ExperimentalStoreApi
    public class Unchanged<out K : StoreKey>(
        public val key: K,
        public val etag: String?,
    ) : RealtimeMessage<K, Nothing>

    /**
     * A per-key stale mark, identical to `Store.invalidate(key)` /
     * `StoreWriteHandle.markStale(key)`.
     *
     * The resident value is kept. Active streams of [key] are signaled to refetch. The mark is
     * durable until a later successful fetch or `confirmFresh`.
     */
    @ExperimentalStoreApi
    public class Changed<out K : StoreKey>(
        public val key: K,
    ) : RealtimeMessage<K, Nothing>

    /**
     * A namespace watermark, identical to `Store.invalidateNamespace(namespace)`.
     *
     * The watermark covers keys in [namespace] whether or not they are currently resident.
     */
    @ExperimentalStoreApi
    public class ChangedNamespace(
        public val namespace: StoreNamespace,
    ) : RealtimeMessage<Nothing, Nothing>

    /**
     * A global watermark, identical to `Store.invalidateAll()`.
     *
     * The watermark covers every namespace, including keys that are not currently resident.
     */
    @ExperimentalStoreApi
    public data object ChangedAll : RealtimeMessage<Nothing, Nothing>

    /**
     * A destructive removal, identical to `Store.clear(key)`.
     *
     * On return the resident value is gone. Active streams observe the absent-value transition
     * and then refetched data.
     */
    @ExperimentalStoreApi
    public class Deleted<out K : StoreKey>(
        public val key: K,
    ) : RealtimeMessage<K, Nothing>
}
