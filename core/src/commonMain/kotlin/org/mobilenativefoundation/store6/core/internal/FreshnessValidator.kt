@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.seam.FetchPlan
import org.mobilenativefoundation.store6.core.seam.FreshnessContext
import org.mobilenativefoundation.store6.core.seam.FreshnessValidator
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal val FetchPlan.servesResident: Boolean
    get() =
        when (this) {
            FetchPlan.Skip -> true
            is FetchPlan.Fetch -> servesResidentWhileFetching
            is FetchPlan.Conditional -> servesResidentWhileFetching
        }

/**
 * Returns elapsed age with the wall-clock posture: missing metadata and backward clocks
 * are zero, while positive subtraction overflow saturates to [Long.MAX_VALUE] milliseconds.
 */
internal fun elapsedAge(
    nowEpochMillis: Long,
    meta: StoreMeta?,
): Duration {
    if (meta == null) return Duration.ZERO
    val writtenAtEpochMillis = meta.writtenAtEpochMillis
    val elapsedMillis =
        if (nowEpochMillis <= writtenAtEpochMillis) {
            0L
        } else {
            val delta = nowEpochMillis - writtenAtEpochMillis
            if (delta < 0L) Long.MAX_VALUE else delta
        }
    return elapsedMillis.milliseconds
}

/**
 * The zero-configuration policy table.
 *
 * Negative wall-clock deltas are clamped to zero before evaluating [Freshness.MaxAge].
 */
@OptIn(DelicateStoreApi::class)
internal object DefaultFreshnessValidator : FreshnessValidator {
    override fun plan(context: FreshnessContext): FetchPlan =
        when (val freshness = context.freshness) {
            Freshness.LocalOnly -> FetchPlan.Skip
            Freshness.MustBeFresh ->
                context.fetchPlan(servesResidentWhileFetching = false)
            Freshness.CachedOrFetch,
            Freshness.StaleIfError,
            -> context.cachedOrFetchPlan()

            is Freshness.MaxAge -> context.maxAgePlan(freshness)
        }

    private fun FreshnessContext.cachedOrFetchPlan(): FetchPlan =
        if (
            !hasResidentValue ||
            meta == null ||
            epochStale ||
            status?.durablyStale == true
        ) {
            fetchPlan(servesResidentWhileFetching = hasResidentValue)
        } else {
            FetchPlan.Skip
        }

    private fun FreshnessContext.maxAgePlan(freshness: Freshness.MaxAge): FetchPlan {
        if (!hasResidentValue) {
            return fetchPlan(servesResidentWhileFetching = false)
        }

        val overAge = meta == null || elapsedAge(nowEpochMillis, meta) > freshness.notOlderThan

        return if (epochStale || status?.durablyStale == true || overAge) {
            fetchPlan(servesResidentWhileFetching = false)
        } else {
            FetchPlan.Skip
        }
    }

    private fun FreshnessContext.fetchPlan(servesResidentWhileFetching: Boolean): FetchPlan {
        val etag = meta?.etag
        return if (hasResidentValue && etag != null) {
            FetchPlan.Conditional(etag, servesResidentWhileFetching)
        } else {
            FetchPlan.Fetch(servesResidentWhileFetching)
        }
    }
}
