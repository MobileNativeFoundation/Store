@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.conflicts

import org.mobilenativefoundation.store6.mutations.MutationConflictResolution
import org.mobilenativefoundation.store6.mutations.MutationPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class MutationMergesTerminalsTest {

    @Test
    fun serverWins_returnsServerWinsForEveryPresenceCombination() {
        val presences: List<MutationPresence<String>> = listOf(
            MutationPresence.Present("value"),
            MutationPresence.Absent,
        )
        val combinations = presences.flatMap { base ->
            presences.flatMap { mine ->
                presences.map { theirs -> Triple(base, mine, theirs) }
            }
        }

        assertEquals(8, combinations.size)
        combinations.forEach { (base, mine, theirs) ->
            assertIs<MutationConflictResolution.ServerWins>(
                MutationMerges.serverWins<String>()(base, mine, theirs),
            )
        }
    }

    @Test
    fun clientWins_returnsRetryMineWhenMinePresent() {
        val mine: MutationPresence<String> = MutationPresence.Present("mine")

        val resolution = MutationMerges.clientWins<String>()(
            MutationPresence.Present("base"),
            mine,
            MutationPresence.Present("theirs"),
        )

        assertIs<MutationConflictResolution.Retry<String>>(resolution)
        assertSame(mine, resolution.value)
    }

    @Test
    fun clientWins_returnsRetryAbsentWhenMineAbsent() {
        val mine: MutationPresence<String> = MutationPresence.Absent

        val resolution = MutationMerges.clientWins<String>()(
            MutationPresence.Present("base"),
            mine,
            MutationPresence.Present("theirs"),
        )

        assertIs<MutationConflictResolution.Retry<String>>(resolution)
        assertSame(mine, resolution.value)
    }

    @Test
    fun clientWins_returnsSameMineInstance() {
        val mine: MutationPresence<String> = MutationPresence.Present(charArrayOf('m').concatToString())

        val resolution = MutationMerges.clientWins<String>()(
            MutationPresence.Absent,
            mine,
            MutationPresence.Absent,
        )

        assertIs<MutationConflictResolution.Retry<String>>(resolution)
        assertSame(mine, resolution.value)
    }
}
