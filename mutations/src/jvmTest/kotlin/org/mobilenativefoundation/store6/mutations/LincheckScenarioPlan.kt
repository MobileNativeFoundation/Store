package org.mobilenativefoundation.store6.mutations

import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import kotlin.random.Random

/**
 * The `@Operation`s of [MutationJournalLincheckTest], named so that a scenario plan can be built,
 * sharded and printed without touching Lincheck's own generator.
 *
 * [runOnce] mirrors `@Operation(runOnce = true)`: the sequential specification allocates a slot
 * once, so a repeated append or retire would diverge from the implementation for a reason that has
 * nothing to do with linearizability.
 */
internal enum class LincheckOperation(val runOnce: Boolean) {
    APPEND_A(runOnce = true),
    APPEND_B(runOnce = true),
    RETIRE_A(runOnce = true),
    RETIRE_B(runOnce = true),
    CONFIRM_TWO(runOnce = true),
    PRUNE(runOnce = false),
    HYDRATE(runOnce = false),
}

/** One shard of the plan: [index] is 1-based and at most [count]. */
internal data class LincheckShard(val index: Int, val count: Int) {
    override fun toString(): String = "$index/$count"
}

/** A single parallel scenario: [LincheckScenarioPlan.THREAD_COUNT] threads of equal length. */
internal data class LincheckScenarioSpec(
    val index: Int,
    val threads: List<List<LincheckOperation>>,
)

/**
 * The deterministic scenario plan for the Lincheck lane.
 *
 * Lincheck 2.39 seeds its `RandomProvider` with a constant `0L` and exposes no seed option, so
 * shards cut by giving each job fewer `iterations` would replay the same scenarios in every shard.
 * The plan is therefore generated here from [SCENARIO_SEED] and registered with
 * `Options.addCustomScenario`, with `iterations(0)` suppressing Lincheck's own random scenarios.
 *
 * The plan has [SCENARIO_COUNT] entries: [GENERATED_SCENARIO_COUNT] drawn from the seed, followed
 * by the curated regression scenario at [CURATED_SCENARIO_INDEX]. **Index [CURATED_SCENARIO_INDEX]
 * is hand-written and must never be regenerated**: it has pinned a cross-slot retirement ordering
 * (`appendA/retireB` against `appendB/retireA`, with `confirmTwo/prune` racing both) since this
 * module was created, and a random draw is not guaranteed to reproduce it.
 *
 * Scenario `i` belongs to shard `k` of `N` exactly when `i % N == k - 1`, so the shards partition
 * `0 until SCENARIO_COUNT` no matter how many jobs the release owner runs.
 *
 * [SCENARIO_DIGEST] is the golden hash of the whole plan. `LincheckScenarioPlanTest` fails if the
 * generated plan stops matching it, and every shard prints it in its [marker] so the release gate
 * can prove the shards validated one and the same plan. That makes [SCENARIO_SEED] a guarded
 * lever: changing it — or an operation, a shape constant, or the curated scenario — fails the
 * golden test and the release gate rather than silently revalidating a different plan.
 *
 * Scope of that guarantee: the digest hashes operation names and thread/actor structure only
 * (`canonicalForm` joins each scenario's per-actor operation name, actors by `,`, threads by
 * `|`) — it attests the 101 scenarios' shape, not the meaning behind them. It does not cover
 * `MutationJournalLincheckTest`'s mapping from each `LincheckOperation` to the `@Operation`
 * function it actually runs (a silently swapped mapping would not change this digest), nor its
 * `JournalSequentialSpecification`, the reference implementation Lincheck checks against.
 * Reviewers must check both by reading; the digest cannot stand in for it.
 */
internal object LincheckScenarioPlan {
    /** Changing this reshuffles every generated scenario and breaks [SCENARIO_DIGEST]. */
    const val SCENARIO_SEED: Long = 20260911L

    /** Scenarios drawn from [SCENARIO_SEED]; the curated scenario follows them. */
    const val GENERATED_SCENARIO_COUNT: Int = 100

    /** Index of the hand-written regression scenario. Never regenerate it. */
    const val CURATED_SCENARIO_INDEX: Int = 100

    /** [GENERATED_SCENARIO_COUNT] generated scenarios plus the curated one. */
    const val SCENARIO_COUNT: Int = 101

    const val THREAD_COUNT: Int = 3
    const val ACTORS_PER_THREAD: Int = 3

    /** SHA-256 over the plan's canonical form, truncated. See the class KDoc. */
    const val SCENARIO_DIGEST: String = "353a057d5f715bc8"

    /** JVM system property carrying `k/N`; absent means the whole plan. */
    const val SHARD_PROPERTY: String = "store6.lincheckShard"

    /** Log token the release evidence recorder parses the executed indices and digest from. */
    const val SCENARIO_MARKER: String = "store6-lincheck-scenarios"

    /** The shard that selects every scenario, used when [SHARD_PROPERTY] is absent. */
    val WHOLE_PLAN: LincheckShard = LincheckShard(index = 1, count = 1)

    /**
     * The regression scenario pinned at [CURATED_SCENARIO_INDEX]: each thread appends one slot and
     * retires the other, while a third thread confirms and prunes underneath them.
     */
    val CURATED_SCENARIO: LincheckScenarioSpec =
        scenario(
            index = CURATED_SCENARIO_INDEX,
            threads =
                listOf(
                    listOf(LincheckOperation.APPEND_A, LincheckOperation.RETIRE_B, LincheckOperation.HYDRATE),
                    listOf(LincheckOperation.APPEND_B, LincheckOperation.RETIRE_A, LincheckOperation.HYDRATE),
                    listOf(LincheckOperation.CONFIRM_TWO, LincheckOperation.PRUNE, LincheckOperation.HYDRATE),
                ),
        )

    /**
     * The whole plan for [seed]: a pure function, so two calls are structurally equal and the
     * determinism test cannot pass by comparing one cached list to itself.
     */
    fun generate(seed: Long): List<LincheckScenarioSpec> {
        val random = Random(seed)
        val generated = List(GENERATED_SCENARIO_COUNT) { index -> scenario(index, threads(random)) }
        return readOnly(generated + CURATED_SCENARIO)
    }

    /**
     * A stable hash over the ordered operation names of every scenario. It is built from names in
     * UTF-8, not from enum ordinals or Kotlin hash codes, and formatted under [Locale.ROOT], so the
     * same plan yields the same value on any JVM and under any default locale.
     */
    fun digest(scenarios: List<LincheckScenarioSpec>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(canonicalForm(scenarios).toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> String.format(Locale.ROOT, "%02x", byte) }
            .take(DIGEST_LENGTH)

    fun scenarios(): List<LincheckScenarioSpec> = PLAN

    fun scenarios(shard: LincheckShard): List<LincheckScenarioSpec> =
        readOnly(indices(shard).map { index -> PLAN[index] })

    fun indices(shard: LincheckShard): List<Int> =
        (0 until SCENARIO_COUNT).filter { index -> index % shard.count == shard.index - 1 }

    /** Parses `k/N`. `null` selects the whole plan; anything else malformed fails fast. */
    fun shard(specification: String?): LincheckShard {
        if (specification == null) return WHOLE_PLAN
        val match = SHARD_FORM.matchEntire(specification.trim())
        val index = match?.groupValues?.get(1)?.toIntOrNull()
        val count = match?.groupValues?.get(2)?.toIntOrNull()
        require(index != null && count != null && count in 1..SCENARIO_COUNT && index in 1..count) {
            "$SHARD_PROPERTY must be k/N with 1 <= k <= N <= $SCENARIO_COUNT (1-based shard index), " +
                "for example 1/4; got '$specification'"
        }
        return LincheckShard(index, count)
    }

    /**
     * The evidence line. `digest` covers the whole plan, not the shard, so the release gate can
     * refuse a census whose shards disagree about which plan they validated.
     */
    fun marker(
        shard: LincheckShard,
        indices: List<Int>,
    ): String =
        "$SCENARIO_MARKER shard=$shard count=${indices.size} " +
            "indices=${indices.joinToString(",")} digest=${digest(PLAN)}"

    private val PLAN: List<LincheckScenarioSpec> by lazy { generate(SCENARIO_SEED) }

    /** `index threads`, threads separated by `|` and actors by `,`, one scenario per line. */
    private fun canonicalForm(scenarios: List<LincheckScenarioSpec>): String =
        scenarios.joinToString("\n") { scenario ->
            "${scenario.index} " +
                scenario.threads.joinToString("|") { thread ->
                    thread.joinToString(",") { operation -> operation.name }
                }
        }

    private fun scenario(
        index: Int,
        threads: List<List<LincheckOperation>>,
    ): LincheckScenarioSpec = LincheckScenarioSpec(index, readOnly(threads.map(::readOnly)))

    private fun threads(random: Random): List<List<LincheckOperation>> {
        val allowed = LincheckOperation.entries.toMutableList()
        return List(THREAD_COUNT) {
            List(ACTORS_PER_THREAD) {
                val operation = allowed[random.nextInt(allowed.size)]
                if (operation.runOnce) allowed.remove(operation)
                operation
            }
        }
    }

    /** Callers get the plan, never the list it was built in. */
    private fun <T> readOnly(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())

    private const val DIGEST_LENGTH: Int = 16

    private val SHARD_FORM = Regex("""(\d+)/(\d+)""")
}
