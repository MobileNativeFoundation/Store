@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.conflicts

import org.mobilenativefoundation.store6.mutations.MutationConflictResolution
import org.mobilenativefoundation.store6.mutations.MutationPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class MutationMergesThreeWayTest {

    @Test
    fun threeWay_bothPresent_basePresent_mergerReceivesBaseValue() {
        val baseValue = charArrayOf('b', 'a', 's', 'e').concatToString()
        val mineValue = "mine"
        val theirsValue = "theirs"
        val merged = "merged"
        var receivedBase: String? = "sentinel"
        var receivedMine: String? = null
        var receivedTheirs: String? = null

        val resolution = MutationMerges.threeWay<String> { base, mine, theirs ->
            receivedBase = base
            receivedMine = mine
            receivedTheirs = theirs
            merged
        }(
            MutationPresence.Present(baseValue),
            MutationPresence.Present(mineValue),
            MutationPresence.Present(theirsValue),
        )

        assertIs<MutationConflictResolution.Retry<String>>(resolution)
        val present = assertIs<MutationPresence.Present<String>>(resolution.value)
        assertEquals(merged, present.value)
        assertSame(baseValue, receivedBase)
        assertEquals(mineValue, receivedMine)
        assertEquals(theirsValue, receivedTheirs)
    }

    @Test
    fun threeWay_bothPresent_baseAbsent_mergerReceivesNullBase() {
        val mineValue = "mine"
        val theirsValue = "theirs"
        val merged = "merged"
        var receivedBase: String? = "sentinel"

        val resolution = MutationMerges.threeWay<String> { base, _, _ ->
            receivedBase = base
            merged
        }(
            MutationPresence.Absent,
            MutationPresence.Present(mineValue),
            MutationPresence.Present(theirsValue),
        )

        assertIs<MutationConflictResolution.Retry<String>>(resolution)
        val present = assertIs<MutationPresence.Present<String>>(resolution.value)
        assertEquals(merged, present.value)
        assertNull(receivedBase)
    }

    @Test
    fun threeWay_mergedValueEqualToTheirsStillRetries() {
        val theirsValue = "theirs"
        val resolution = MutationMerges.threeWay<String> { _, _, _ -> theirsValue }(
            MutationPresence.Present("base"),
            MutationPresence.Present("mine"),
            MutationPresence.Present(theirsValue),
        )

        assertIs<MutationConflictResolution.Retry<String>>(resolution)
        val present = assertIs<MutationPresence.Present<String>>(resolution.value)
        assertEquals(theirsValue, present.value)
    }

    @Test
    fun threeWay_mineAbsentTheirsBias_serverWins() {
        val resolution = MutationMerges.threeWay<String>(
            onMineAbsent = MutationConflictBias.THEIRS,
            merge = { _, mine, _ -> mine },
        )(
            MutationPresence.Present("base"),
            MutationPresence.Absent,
            MutationPresence.Present("theirs"),
        )

        assertIs<MutationConflictResolution.ServerWins>(resolution)
    }

    @Test
    fun threeWay_mineAbsentMineBias_retryAbsent() {
        val mine: MutationPresence<String> = MutationPresence.Absent
        val resolution = MutationMerges.threeWay<String>(
            onMineAbsent = MutationConflictBias.MINE,
            merge = { _, mineValue, _ -> mineValue },
        )(
            MutationPresence.Present("base"),
            mine,
            MutationPresence.Present("theirs"),
        )

        assertIs<MutationConflictResolution.Retry<String>>(resolution)
        assertSame(mine, resolution.value)
    }

    @Test
    fun threeWay_theirsAbsentTheirsBias_serverWins() {
        val resolution = MutationMerges.threeWay<String>(
            onTheirsAbsent = MutationConflictBias.THEIRS,
            merge = { _, mine, _ -> mine },
        )(
            MutationPresence.Present("base"),
            MutationPresence.Present("mine"),
            MutationPresence.Absent,
        )

        assertIs<MutationConflictResolution.ServerWins>(resolution)
    }

    @Test
    fun threeWay_theirsAbsentMineBias_retryMine() {
        val mine: MutationPresence<String> = MutationPresence.Present("mine")
        val resolution = MutationMerges.threeWay<String>(
            onTheirsAbsent = MutationConflictBias.MINE,
            merge = { _, mineValue, _ -> mineValue },
        )(
            MutationPresence.Present("base"),
            mine,
            MutationPresence.Absent,
        )

        assertIs<MutationConflictResolution.Retry<String>>(resolution)
        assertSame(mine, resolution.value)
    }

    @Test
    fun threeWay_bothAbsent_serverWins() {
        val resolution = MutationMerges.threeWay<String> { _, mine, _ -> mine }(
            MutationPresence.Present("base"),
            MutationPresence.Absent,
            MutationPresence.Absent,
        )

        assertIs<MutationConflictResolution.ServerWins>(resolution)
    }

    @Test
    fun threeWay_mergerThrowPropagates() {
        val failure = assertFailsWith<IllegalStateException> {
            MutationMerges.threeWay<String> { _, _, _ ->
                throw IllegalStateException("merge failure")
            }(
                MutationPresence.Present("base"),
                MutationPresence.Present("mine"),
                MutationPresence.Present("theirs"),
            )
        }

        assertEquals("merge failure", failure.message)
    }
}
