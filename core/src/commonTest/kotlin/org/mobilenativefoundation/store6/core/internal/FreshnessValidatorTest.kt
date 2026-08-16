@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.seam.FetchPlan
import org.mobilenativefoundation.store6.core.seam.FreshnessContext
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class FreshnessValidatorTest {
    @Test
    fun localOnly_alwaysSkipsAbsentAndStaleResident() {
        assertSame(
            FetchPlan.Skip,
            plan(
                hasResidentValue = false,
                meta = null,
                epochStale = false,
                freshness = Freshness.LocalOnly,
            ),
        )
        assertSame(
            FetchPlan.Skip,
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L),
                epochStale = true,
                freshness = Freshness.LocalOnly,
            ),
        )
    }

    @Test
    fun mustBeFresh_alwaysFetchesWithoutServingResident() {
        assertFetch(
            plan(
                hasResidentValue = false,
                meta = null,
                epochStale = false,
                freshness = Freshness.MustBeFresh,
            ),
            servesResident = false,
        )
        assertFetch(
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L),
                epochStale = true,
                freshness = Freshness.MustBeFresh,
            ),
            servesResident = false,
        )
    }

    @Test
    fun cachedOrFetch_skipsFreshFetchesStaleResidentAndFetchesAbsent() {
        assertSame(
            FetchPlan.Skip,
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L),
                epochStale = false,
                freshness = Freshness.CachedOrFetch,
            ),
        )
        assertFetch(
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L),
                epochStale = true,
                freshness = Freshness.CachedOrFetch,
            ),
            servesResident = true,
        )
        assertFetch(
            plan(
                hasResidentValue = false,
                meta = null,
                epochStale = false,
                freshness = Freshness.CachedOrFetch,
            ),
            servesResident = false,
        )
    }

    @Test
    fun staleIfError_usesCachedOrFetchPlanning() {
        assertSame(
            FetchPlan.Skip,
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L),
                epochStale = false,
                freshness = Freshness.StaleIfError,
            ),
        )
        assertFetch(
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L),
                epochStale = true,
                freshness = Freshness.StaleIfError,
            ),
            servesResident = true,
        )
        assertFetch(
            plan(
                hasResidentValue = false,
                meta = null,
                epochStale = false,
                freshness = Freshness.StaleIfError,
            ),
            servesResident = false,
        )
    }

    @Test
    fun maxAge_enforcesBoundMetadataAndEpoch() {
        val freshness = Freshness.MaxAge(5.minutes)

        assertSame(
            FetchPlan.Skip,
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 540_000L),
                epochStale = false,
                freshness = freshness,
                nowEpochMillis = 600_000L,
            ),
        )
        assertFetch(
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L),
                epochStale = false,
                freshness = freshness,
                nowEpochMillis = 600_000L,
            ),
            servesResident = false,
        )
        assertFetch(
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = Long.MIN_VALUE),
                epochStale = false,
                freshness = freshness,
                nowEpochMillis = Long.MAX_VALUE,
            ),
            servesResident = false,
        )
        assertFetch(
            plan(
                hasResidentValue = true,
                meta = null,
                epochStale = false,
                freshness = freshness,
            ),
            servesResident = false,
        )
        assertFetch(
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 540_000L),
                epochStale = true,
                freshness = freshness,
                nowEpochMillis = 600_000L,
            ),
            servesResident = false,
        )
    }

    @Test
    fun maxAge_treatsNegativeClockDeltaAsFresh() {
        assertSame(
            FetchPlan.Skip,
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 601_000L),
                epochStale = false,
                freshness = Freshness.MaxAge(5.minutes),
                nowEpochMillis = 600_000L,
            ),
        )
        assertSame(
            FetchPlan.Skip,
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = Long.MAX_VALUE),
                epochStale = false,
                freshness = Freshness.MaxAge(Duration.ZERO),
                nowEpochMillis = Long.MIN_VALUE,
            ),
        )
    }

    @Test
    fun conditionalPlanned_whenEtagPresentAndFetchDue() {
        val result =
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L, etag = "e1"),
                epochStale = true,
                freshness = Freshness.CachedOrFetch,
            )

        val conditional = assertIs<FetchPlan.Conditional>(result)
        assertEquals("e1", conditional.etag)
        assertEquals(true, conditional.servesResidentWhileFetching)
    }

    @Test
    fun plainFetchPlanned_whenNoEtag() {
        assertFetch(
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L),
                epochStale = true,
                freshness = Freshness.CachedOrFetch,
            ),
            servesResident = true,
        )
    }

    @Test
    fun maxAge_overBoundWithEtag_plansConditionalWithheld() {
        val result =
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L, etag = "e1"),
                epochStale = false,
                freshness = Freshness.MaxAge(5.minutes),
                nowEpochMillis = 600_000L,
            )

        val conditional = assertIs<FetchPlan.Conditional>(result)
        assertEquals("e1", conditional.etag)
        assertEquals(false, conditional.servesResidentWhileFetching)
    }

    @Test
    fun durablyStale_forcesFetchUnderCachedOrFetchAndMaxAgeWithinBound() {
        val status =
            KeyStatus(
                meta = meta(writtenAtEpochMillis = 599_000L, etag = "e1"),
                lastSuccessSequence = 1L,
                lastFailureAtEpochMillis = null,
                consecutiveFailures = 0,
                durablyStale = true,
            )

        val cached =
            assertIs<FetchPlan.Conditional>(
                plan(
                    hasResidentValue = true,
                    meta = status.meta,
                    epochStale = false,
                    freshness = Freshness.CachedOrFetch,
                    nowEpochMillis = 600_000L,
                    status = status,
                ),
            )
        assertEquals(true, cached.servesResidentWhileFetching)

        val maxAge =
            assertIs<FetchPlan.Conditional>(
                plan(
                    hasResidentValue = true,
                    meta = status.meta,
                    epochStale = false,
                    freshness = Freshness.MaxAge(5.minutes),
                    nowEpochMillis = 600_000L,
                    status = status,
                ),
            )
        assertEquals(false, maxAge.servesResidentWhileFetching)
    }

    @Test
    fun mustBeFresh_withEtagAndResident_plansConditionalWithheld() {
        val result =
            plan(
                hasResidentValue = true,
                meta = meta(writtenAtEpochMillis = 0L, etag = "e1"),
                epochStale = false,
                freshness = Freshness.MustBeFresh,
            )

        val conditional = assertIs<FetchPlan.Conditional>(result)
        assertEquals("e1", conditional.etag)
        assertEquals(false, conditional.servesResidentWhileFetching)
    }

    private fun plan(
        hasResidentValue: Boolean,
        meta: StoreMeta?,
        epochStale: Boolean,
        freshness: Freshness,
        nowEpochMillis: Long = 0L,
        status: KeyStatus? = null,
    ): FetchPlan =
        DefaultFreshnessValidator.plan(
            FreshnessContext(
                hasResidentValue = hasResidentValue,
                meta = meta,
                epochStale = epochStale,
                freshness = freshness,
                nowEpochMillis = nowEpochMillis,
                status = status,
            ),
        )

    private fun meta(
        writtenAtEpochMillis: Long,
        etag: String? = null,
    ): EngineStoreMeta =
        EngineStoreMeta(
            writtenAtEpochMillis = writtenAtEpochMillis,
            etag = etag,
        )

    private fun assertFetch(
        plan: FetchPlan,
        servesResident: Boolean,
    ) {
        assertEquals(servesResident, assertIs<FetchPlan.Fetch>(plan).servesResidentWhileFetching)
    }
}
