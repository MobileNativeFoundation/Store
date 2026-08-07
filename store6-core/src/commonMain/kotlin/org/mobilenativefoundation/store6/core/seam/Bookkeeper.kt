package org.mobilenativefoundation.store6.core.seam

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace

/**
 * Tracks successful metadata, consecutive failures, and durable staleness for canonical keys.
 *
 * Every per-key operation derives identity exclusively from
 * `(key.namespace.value, key.canonicalId())`; object identity and concrete key class are never part
 * of bookkeeping identity. Every namespace operation similarly normalizes identity exclusively by
 * `namespace.value`; [StoreNamespace] object identity is never part of namespace matching.
 * Implementations use one store-local monotone sequence shared by every success, per-key stale
 * mark, namespace watermark, and global watermark. A key is durably stale exactly when
 * `max(mark/ns/global) > (success ?: 0)`. Therefore a failure-only record is not durably stale until
 * covered by a positive mark or watermark, and a later success clears earlier staleness.
 *
 * [recordSuccess], [recordFailure], and operational per-key [forget] are operationally infallible:
 * implementations absorb or report their own storage failures and do not throw them through this
 * interface. Cooperative cancellation may still propagate. [recordSuccess] clears the prior
 * failure timestamp and count.
 *
 * The maintenance methods [markStale], [advanceStaleWatermark],
 * [advanceGlobalStaleWatermark], [forgetNamespace], and [forgetAll] may report storage failures by
 * throwing. Each is exception-atomic for every [Throwable], including cancellation: normal return
 * means the full operation was applied, while throwing means it had no effect. Forget operations
 * remove key records but never reset namespace or global watermarks, and watermarks otherwise only
 * advance.
 */
@ExperimentalStoreApi
@SubclassOptInRequired(DelicateStoreApi::class)
public interface Bookkeeper {
    /**
     * Records successful metadata for [key], assigns the next shared store-local monotone sequence,
     * and clears its failure timestamp and count.
     */
    public suspend fun recordSuccess(
        key: StoreKey,
        meta: StoreMeta,
    )

    /**
     * Records one consecutive failure for [key] at [atEpochMillis] without making a failure-only
     * record durably stale.
     */
    public suspend fun recordFailure(
        key: StoreKey,
        atEpochMillis: Long,
    )

    /**
     * Returns canonical status for [key], including watermark-only staleness, or null when neither a
     * record nor a covering watermark exists.
     */
    public suspend fun status(key: StoreKey): KeyStatus?

    /**
     * Forgets [key]'s record, including its per-key stale mark, without resetting namespace or
     * global watermarks.
     */
    public suspend fun forget(key: StoreKey)

    /**
     * Assigns the next shared sequence to [key]'s stale mark as one exception-atomic, fallible
     * maintenance operation; the shared sequence never resets.
     */
    public suspend fun markStale(key: StoreKey)

    /**
     * Assigns the next shared sequence to durable stale coverage for every key in [namespace] as
     * one exception-atomic, fallible maintenance operation; the watermark never resets.
     */
    public suspend fun advanceStaleWatermark(namespace: StoreNamespace)

    /**
     * Assigns the next shared sequence to global stale coverage as one exception-atomic, fallible
     * maintenance operation; the watermark never resets.
     */
    public suspend fun advanceGlobalStaleWatermark()

    /**
     * Forgets key records in [namespace] without resetting watermarks as one exception-atomic,
     * fallible maintenance operation.
     */
    public suspend fun forgetNamespace(namespace: StoreNamespace)

    /**
     * Forgets all key records without resetting watermarks as one exception-atomic, fallible
     * maintenance operation.
     */
    public suspend fun forgetAll()
}

/**
 * Immutable bookkeeping state for one canonical key.
 *
 * [durablyStale] reflects the exact watermark algebra `max(mark/ns/global) > (success ?: 0)`.
 * A failure-only record therefore reports false until a mark or watermark covers it.
 */
@ExperimentalStoreApi
public class KeyStatus(
    /** Metadata from the latest recorded success, or null when none has been recorded. */
    public val meta: StoreMeta?,

    /** The shared monotone sequence assigned to the latest success, or null when none exists. */
    public val lastSuccessSequence: Long?,

    /** The time of the latest [Bookkeeper.recordFailure], null once a success clears it. */
    public val lastFailureAtEpochMillis: Long?,

    /** Failures recorded since the latest success; zero once a success clears the streak. */
    public val consecutiveFailures: Int,

    /** Whether a key, namespace, or global stale sequence outranks this key's latest success. */
    public val durablyStale: Boolean,
)
