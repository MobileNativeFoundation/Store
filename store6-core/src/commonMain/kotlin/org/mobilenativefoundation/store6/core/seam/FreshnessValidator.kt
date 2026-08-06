package org.mobilenativefoundation.store6.core.seam

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreMeta

/**
 * The value and bookkeeping facts used to plan a read.
 *
 * [status] carries the durable bookkeeping posture captured before the corresponding engine-state
 * snapshot. A resident value with null [meta] is treated as conservatively stale.
 */
@ExperimentalStoreApi
public class FreshnessContext(
    public val hasResidentValue: Boolean,
    public val meta: StoreMeta?,
    public val epochStale: Boolean,
    public val freshness: Freshness,
    public val nowEpochMillis: Long,
    public val status: KeyStatus? = null,
)

/**
 * Selects the fetch plan for one coherent [FreshnessContext].
 */
@ExperimentalStoreApi
@SubclassOptInRequired(DelicateStoreApi::class)
public interface FreshnessValidator {
    /** Plans whether and how the current read should fetch as a pure function of [context]. */
    public fun plan(context: FreshnessContext): FetchPlan
}

/**
 * Fetch action selected by a [FreshnessValidator].
 */
@ExperimentalStoreApi
public sealed interface FetchPlan {
    /**
     * Skips fetching. Skip with no resident value yields [StoreError.Missing] (get throws, stream
     * emits Error).
     */
    public data object Skip : FetchPlan

    /**
     * Performs an unconditional fetch and optionally serves the resident value while it runs.
     */
    public class Fetch(
        public val servesResidentWhileFetching: Boolean,
    ) : FetchPlan

    /**
     * Performs a conditional fetch for [etag] and optionally serves residence while it runs.
     */
    public class Conditional(
        public val etag: String,
        public val servesResidentWhileFetching: Boolean,
    ) : FetchPlan
}
