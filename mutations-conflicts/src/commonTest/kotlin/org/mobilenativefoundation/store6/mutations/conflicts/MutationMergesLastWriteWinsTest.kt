@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.conflicts

import org.mobilenativefoundation.store6.mutations.MutationConflictResolution
import org.mobilenativefoundation.store6.mutations.MutationPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class MutationMergesLastWriteWinsTest {

    @Test
    fun lastWriteWins_newerMineRetriesMine() {
        val mine: MutationPresence<StampedValue> = MutationPresence.Present(StampedValue(2))
        val resolution = MutationMerges.lastWriteWins<StampedValue> { it.stamp }(
            MutationPresence.Absent,
            mine,
            MutationPresence.Present(StampedValue(1)),
        )

        assertIs<MutationConflictResolution.Retry<StampedValue>>(resolution)
        assertSame(mine, resolution.value)
    }

    @Test
    fun lastWriteWins_tieResolvesServerWins() {
        val resolution = MutationMerges.lastWriteWins<StampedValue> { it.stamp }(
            MutationPresence.Absent,
            MutationPresence.Present(StampedValue(1)),
            MutationPresence.Present(StampedValue(1)),
        )

        assertIs<MutationConflictResolution.ServerWins>(resolution)
    }

    @Test
    fun lastWriteWins_olderMineResolvesServerWins() {
        val resolution = MutationMerges.lastWriteWins<StampedValue> { it.stamp }(
            MutationPresence.Absent,
            MutationPresence.Present(StampedValue(1)),
            MutationPresence.Present(StampedValue(2)),
        )

        assertIs<MutationConflictResolution.ServerWins>(resolution)
    }

    @Test
    fun lastWriteWins_mineAbsentTheirsBias_serverWins() {
        val resolution = MutationMerges.lastWriteWins(
            onMineAbsent = MutationConflictBias.THEIRS,
            writtenAt = StampedValue::stamp,
        )(
            MutationPresence.Present(StampedValue(0)),
            MutationPresence.Absent,
            MutationPresence.Present(StampedValue(1)),
        )

        assertIs<MutationConflictResolution.ServerWins>(resolution)
    }

    @Test
    fun lastWriteWins_mineAbsentMineBias_retryAbsent() {
        val mine: MutationPresence<StampedValue> = MutationPresence.Absent
        val resolution = MutationMerges.lastWriteWins(
            onMineAbsent = MutationConflictBias.MINE,
            writtenAt = StampedValue::stamp,
        )(
            MutationPresence.Present(StampedValue(0)),
            mine,
            MutationPresence.Present(StampedValue(1)),
        )

        assertIs<MutationConflictResolution.Retry<StampedValue>>(resolution)
        assertSame(mine, resolution.value)
    }

    @Test
    fun lastWriteWins_theirsAbsentTheirsBias_serverWins() {
        val resolution = MutationMerges.lastWriteWins(
            onTheirsAbsent = MutationConflictBias.THEIRS,
            writtenAt = StampedValue::stamp,
        )(
            MutationPresence.Present(StampedValue(0)),
            MutationPresence.Present(StampedValue(1)),
            MutationPresence.Absent,
        )

        assertIs<MutationConflictResolution.ServerWins>(resolution)
    }

    @Test
    fun lastWriteWins_theirsAbsentMineBias_retryMine() {
        val mine: MutationPresence<StampedValue> = MutationPresence.Present(StampedValue(1))
        val resolution = MutationMerges.lastWriteWins(
            onTheirsAbsent = MutationConflictBias.MINE,
            writtenAt = StampedValue::stamp,
        )(
            MutationPresence.Present(StampedValue(0)),
            mine,
            MutationPresence.Absent,
        )

        assertIs<MutationConflictResolution.Retry<StampedValue>>(resolution)
        assertSame(mine, resolution.value)
    }

    @Test
    fun lastWriteWins_bothAbsent_serverWins() {
        val resolution = MutationMerges.lastWriteWins<StampedValue> { it.stamp }(
            MutationPresence.Present(StampedValue(0)),
            MutationPresence.Absent,
            MutationPresence.Absent,
        )

        assertIs<MutationConflictResolution.ServerWins>(resolution)
    }

    @Test
    fun lastWriteWins_writtenAtThrowPropagates() {
        val failure = assertFailsWith<IllegalStateException> {
            MutationMerges.lastWriteWins<StampedValue> {
                throw IllegalStateException("stamp failure")
            }(
                MutationPresence.Absent,
                MutationPresence.Present(StampedValue(2)),
                MutationPresence.Present(StampedValue(1)),
            )
        }

        assertEquals("stamp failure", failure.message)
    }

    private data class StampedValue(val stamp: Long)
}
