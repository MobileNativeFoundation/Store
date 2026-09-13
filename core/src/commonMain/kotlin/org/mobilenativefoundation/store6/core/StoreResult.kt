package org.mobilenativefoundation.store6.core

import kotlin.time.Duration

/**
 * A state or value reported while observing a [Store].
 *
 * @param V the type of data carried by [Data]
 */
public sealed interface StoreResult<out V> {
    /** Indicates that the store has no servable resident value under the policy in effect. */
    public class Loading internal constructor() : StoreResult<Nothing>

    /**
     * A value available from the store.
     *
     * @param V the value type
     */
    public class Data<V> internal constructor(
        /** The value produced by the store. */
        public val value: V,

        /** The source from which `value` was obtained. */
        public val origin: Origin,

        /** Elapsed time since `value` was committed to the store. */
        public val age: Duration,

        /**
         * Whether the value was invalidated, or exceeds the age bound of the freshness policy in
         * effect for this observation.
         */
        public val isStale: Boolean,

        /** Whether a fetch was in flight for this key when this result was emitted. */
        public val refreshing: Boolean,
    ) : StoreResult<V>

    /**
     * Confirmation that the current value is still fresh without a new value being produced —
     * the not-modified signal of a conditional fetch.
     *
     * Emitted when a conditional fetch returns not-modified: the value is server-confirmed fresh,
     * metadata is refreshed, and `age` is the elapsed time since the last commit measured at
     * revalidation. Revalidated is a lifecycle signal: `conflateLatestData` never conflates it
     * away in favor of another kind; for a blocked collector a newer `Revalidated` supersedes an
     * older queued one, so the kind itself is never lost.
     */
    public class Revalidated internal constructor(
        /** Elapsed time since the value was last committed, measured at revalidation. */
        public val age: Duration,
    ) : StoreResult<Nothing>

    /**
     * A retrieval failure; it terminates the observed stream only in the MustBeFresh initial
     * cycle, otherwise the stream stays live.
     */
    public class Error internal constructor(
        /** The structured error describing the failure. */
        public val error: StoreError,

        /**
         * Whether a stale value was served alongside this failure under a stale-tolerant policy.
         *
         * `true` when an invalidated resident was served and its refresh failed under
         * [Freshness.CachedOrFetch] or [Freshness.StaleIfError]; `false` when no resident was
         * served or the policy withheld it.
         */
        public val servedStale: Boolean,
    ) : StoreResult<Nothing>
}
