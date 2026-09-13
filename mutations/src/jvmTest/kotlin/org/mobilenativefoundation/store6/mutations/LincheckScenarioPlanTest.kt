package org.mobilenativefoundation.store6.mutations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Lincheck lane is sharded across CI jobs, so the scenario list must be a pure function of a
 * named seed rather than of Lincheck's own generator: Lincheck 2.39 seeds `RandomProvider` with a
 * constant and exposes no seed option, so every shard would otherwise replay the same scenarios.
 */
class LincheckScenarioPlanTest {
    @Test
    fun generatingTwiceFromTheSameSeedProducesIdenticalScenarios() {
        val first = LincheckScenarioPlan.generate(LincheckScenarioPlan.SCENARIO_SEED)
        val second = LincheckScenarioPlan.generate(LincheckScenarioPlan.SCENARIO_SEED)
        assertEquals(first, second)
        assertEquals(LincheckScenarioPlan.SCENARIO_COUNT, first.size)
        assertEquals((0 until LincheckScenarioPlan.SCENARIO_COUNT).toList(), first.map(LincheckScenarioSpec::index))
        assertEquals(first, LincheckScenarioPlan.scenarios())
    }

    @Test
    fun thePlanMatchesItsCheckedInGoldenDigest() {
        assertEquals(
            LincheckScenarioPlan.SCENARIO_DIGEST,
            LincheckScenarioPlan.digest(LincheckScenarioPlan.generate(LincheckScenarioPlan.SCENARIO_SEED)),
            "the checked-in golden digest no longer describes the plan this seed generates",
        )
        assertEquals(
            LincheckScenarioPlan.SCENARIO_DIGEST,
            LincheckScenarioPlan.digest(LincheckScenarioPlan.scenarios()),
        )
    }

    @Test
    fun theDigestMovesWhenTheSeedOrAnOperationMoves() {
        assertNotEquals(
            LincheckScenarioPlan.SCENARIO_DIGEST,
            LincheckScenarioPlan.digest(LincheckScenarioPlan.generate(LincheckScenarioPlan.SCENARIO_SEED + 1L)),
        )
        val edited =
            LincheckScenarioPlan.scenarios().toMutableList().also { plan ->
                plan[0] = plan[0].copy(threads = plan[0].threads.reversed())
            }
        assertNotEquals(LincheckScenarioPlan.SCENARIO_DIGEST, LincheckScenarioPlan.digest(edited))
    }

    @Test
    fun theCuratedRegressionScenarioIsPinnedAtTheEndOfThePlan() {
        assertEquals(LincheckScenarioPlan.GENERATED_SCENARIO_COUNT + 1, LincheckScenarioPlan.SCENARIO_COUNT)
        assertEquals(LincheckScenarioPlan.GENERATED_SCENARIO_COUNT, LincheckScenarioPlan.CURATED_SCENARIO_INDEX)
        val curated = LincheckScenarioPlan.scenarios()[LincheckScenarioPlan.CURATED_SCENARIO_INDEX]
        assertEquals(LincheckScenarioPlan.CURATED_SCENARIO_INDEX, curated.index)
        assertEquals(
            listOf(
                listOf(LincheckOperation.APPEND_A, LincheckOperation.RETIRE_B, LincheckOperation.HYDRATE),
                listOf(LincheckOperation.APPEND_B, LincheckOperation.RETIRE_A, LincheckOperation.HYDRATE),
                listOf(LincheckOperation.CONFIRM_TWO, LincheckOperation.PRUNE, LincheckOperation.HYDRATE),
            ),
            curated.threads,
        )
    }

    @Test
    fun thePlanIsReadOnlyToItsCallers() {
        @Suppress("UNCHECKED_CAST")
        val plan = LincheckScenarioPlan.scenarios() as MutableList<LincheckScenarioSpec>
        assertFailsWith<UnsupportedOperationException> { plan.clear() }
        assertEquals(LincheckScenarioPlan.SCENARIO_COUNT, LincheckScenarioPlan.scenarios().size)
    }

    @Test
    fun everyScenarioKeepsTheThreeByThreeParallelShape() {
        LincheckScenarioPlan.scenarios().forEach { scenario ->
            assertEquals(LincheckScenarioPlan.THREAD_COUNT, scenario.threads.size)
            scenario.threads.forEach { actors ->
                assertEquals(LincheckScenarioPlan.ACTORS_PER_THREAD, actors.size)
            }
        }
    }

    @Test
    fun runOnceOperationsAppearAtMostOncePerScenario() {
        LincheckScenarioPlan.scenarios().forEach { scenario ->
            val runOnce = scenario.threads.flatten().filter(LincheckOperation::runOnce)
            assertEquals(runOnce.toSet().size, runOnce.size, "run-once repeat in scenario ${scenario.index}")
        }
    }

    @Test
    fun everyOperationIsReachableAcrossThePlan() {
        val used = LincheckScenarioPlan.scenarios().flatMap { it.threads.flatten() }.toSet()
        assertEquals(LincheckOperation.entries.toSet(), used)
    }

    @Test
    fun shardsPartitionEveryScenarioExactlyOnce() {
        for (count in 1..8) {
            val union = mutableListOf<Int>()
            for (index in 1..count) {
                val selected = LincheckScenarioPlan.indices(LincheckShard(index, count))
                assertTrue(selected.isNotEmpty(), "empty shard $index/$count")
                assertEquals(selected.sorted(), selected)
                union += selected
            }
            assertEquals((0 until LincheckScenarioPlan.SCENARIO_COUNT).toList(), union.sorted())
            assertEquals(union.size, union.toSet().size, "overlapping shards for N=$count")
        }
    }

    @Test
    fun shardScenariosFollowTheShardIndices() {
        val shard = LincheckShard(2, 4)
        assertEquals(
            LincheckScenarioPlan.indices(shard),
            LincheckScenarioPlan.scenarios(shard).map(LincheckScenarioSpec::index),
        )
        assertEquals(25, LincheckScenarioPlan.scenarios(shard).size)
        // 101 scenarios over four shards: the first one carries the curated scenario and one extra.
        assertEquals(26, LincheckScenarioPlan.scenarios(LincheckShard(1, 4)).size)
        assertTrue(LincheckScenarioPlan.CURATED_SCENARIO_INDEX in LincheckScenarioPlan.indices(LincheckShard(1, 4)))
    }

    @Test
    fun anAbsentShardSpecificationSelectsEveryScenario() {
        val shard = LincheckScenarioPlan.shard(null)
        assertEquals(LincheckShard(1, 1), shard)
        assertEquals(LincheckScenarioPlan.scenarios(), LincheckScenarioPlan.scenarios(shard))
    }

    @Test
    fun wellFormedShardSpecificationsParse() {
        assertEquals(LincheckShard(1, 4), LincheckScenarioPlan.shard("1/4"))
        assertEquals(LincheckShard(4, 4), LincheckScenarioPlan.shard(" 4/4 "))
        assertEquals(LincheckShard(101, 101), LincheckScenarioPlan.shard("101/101"))
    }

    @Test
    fun malformedOrOutOfRangeShardSpecificationsAreRejected() {
        val rejected = listOf("", "   ", "1", "4", "0/4", "5/4", "-1/4", "1/0", "1/-4", "1/102",
                              "1/4/2", "a/b", "1 / 4", "1,4", "one/four", "1.0/4")
        rejected.forEach { specification ->
            val failure = assertFailsWith<IllegalArgumentException>("accepted '$specification'") {
                LincheckScenarioPlan.shard(specification)
            }
            assertTrue(
                LincheckScenarioPlan.SHARD_PROPERTY in failure.message.orEmpty() &&
                    "k/N" in failure.message.orEmpty(),
                "message must name the expected form: ${failure.message}",
            )
        }
    }

    @Test
    fun theScenarioMarkerNamesTheShardItsIndicesAndTheWholePlansDigest() {
        val shard = LincheckShard(1, 4)
        val indices = LincheckScenarioPlan.indices(shard)
        assertEquals(
            "${LincheckScenarioPlan.SCENARIO_MARKER} shard=1/4 count=26 " +
                "indices=" + (0..100).filter { it % 4 == 0 }.joinToString(",") +
                " digest=${LincheckScenarioPlan.SCENARIO_DIGEST}",
            LincheckScenarioPlan.marker(shard, indices),
        )
    }
}
