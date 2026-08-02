@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.testing

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationPresence
import org.mobilenativefoundation.store6.mutations.MutatorRef
import org.mobilenativefoundation.store6.mutations.MutatorRegistry
import org.mobilenativefoundation.store6.mutations.MutatorRegistryBuilder
import org.mobilenativefoundation.store6.mutations.StaleSet
import org.mobilenativefoundation.store6.mutations.mutatorRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One representative input pair for [MutatorPurityContractKit].
 *
 * The factories must return fresh, structurally equivalent values on every call. Fresh values
 * keep one projection invocation from mutating the inputs observed by a later invocation.
 */
@ExperimentalStoreApi
public class MutatorPuritySample<V : Any, A : Any>(
    /** Human-readable case name included in assertion failures. */
    public val name: String,
    internal val newBase: () -> MutationPresence<V>,
    internal val newArgs: () -> A,
) {
    init {
        require(name.isNotBlank()) { "A mutator-purity sample name must not be blank." }
    }
}

/**
 * One bounded ambient-state transition exercised by [MutatorPurityContractKit].
 *
 * Arbitrary ambient state cannot be discovered by a black-box kit. Consumers therefore name the
 * state relevant to their projector and provide baseline/changed transitions plus restoration.
 */
@ExperimentalStoreApi
public class MutatorAmbientProbe(
    /** Human-readable probe name included in assertion failures. */
    public val name: String,
    internal val enterBaseline: () -> Unit,
    internal val enterChanged: () -> Unit,
    internal val restore: () -> Unit,
) {
    init {
        require(name.isNotBlank()) { "A mutator ambient-probe name must not be blank." }
    }
}

/**
 * A registry-bound projector subject consumed by [MutatorPurityContractKit].
 *
 * Construct subjects with [mutatorPuritySubject]. That factory installs and retains the same
 * projector lambda, so the tested function cannot drift from the function registered under [ref].
 */
@ExperimentalStoreApi
public class MutatorPuritySubject<K : StoreKey, V : Any, A : Any, S : Any> internal constructor(
    /** Registry containing the projector under test. */
    public val registry: MutatorRegistry<K, V>,
    /** Typed reference returned by the exact registration under test. */
    public val ref: MutatorRef<K, V, A>,
    internal val samples: List<MutatorPuritySample<V, A>>,
    internal val ambientProbes: List<MutatorAmbientProbe>,
    internal val snapshotValue: (V) -> S,
    internal val snapshotArgs: (A) -> List<Byte>,
    internal val project: (MutationPresence<V>, A) -> MutationPresence<V>?,
)

/**
 * Registers a projector and returns the exact registry-bound subject used by the purity kit.
 *
 * [snapshotValue] must return detached structural data suitable for equality checks. This is
 * required because `MutationPresence.Present` deliberately does not define structural equality.
 * [ambientProbes] bounds the external state the consumer claims the projector ignores.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any, A : Any, S : Any> mutatorPuritySubject(
    id: String,
    version: Int,
    codec: MutationCodec<A>,
    stales: (K, A) -> StaleSet<K>,
    samples: List<MutatorPuritySample<V, A>>,
    snapshotValue: (V) -> S,
    ambientProbes: List<MutatorAmbientProbe>,
    configure: MutatorRegistryBuilder<K, V>.() -> Unit = {},
    project: (MutationPresence<V>, A) -> MutationPresence<V>?,
): MutatorPuritySubject<K, V, A, S> {
    require(samples.isNotEmpty()) { "Mutator purity requires at least one base/args sample." }
    require(ambientProbes.isNotEmpty()) { "Mutator purity requires at least one ambient-state probe." }
    require(samples.map { sample -> sample.name }.distinct().size == samples.size) {
        "Mutator-purity sample names must be unique."
    }
    require(ambientProbes.map { probe -> probe.name }.distinct().size == ambientProbes.size) {
        "Mutator ambient-probe names must be unique."
    }

    lateinit var ref: MutatorRef<K, V, A>
    val registry =
        mutatorRegistry<K, V> {
            configure()
            ref =
                mutator(
                    id = id,
                    version = version,
                    codec = codec,
                    stales = stales,
                    project = project,
                )
        }
    return MutatorPuritySubject(
        registry = registry,
        ref = ref,
        samples = samples.toList(),
        ambientProbes = ambientProbes.toList(),
        snapshotValue = snapshotValue,
        snapshotArgs = { args -> codec.encode(args).toList() },
        project = project,
    )
}

/**
 * Published TD-12 conformance kit for durable mutator projectors.
 *
 * Extend this class in a consumer test source set and return a subject built by
 * [mutatorPuritySubject]. The inherited tests prove, over the supplied representative samples and
 * ambient probes, that local application is a pure deterministic function of `(base, args)`.
 * Double application compares two independent two-step replay traces; it does not require
 * idempotence, which would incorrectly reject lawful append and increment mutators.
 */
@ExperimentalStoreApi
public abstract class MutatorPurityContractKit<K : StoreKey, V : Any, A : Any, S : Any> {
    /** Creates a fresh registry-bound subject for one inherited contract test. */
    public abstract fun createSubject(): MutatorPuritySubject<K, V, A, S>

    /** Same fresh input pair always produces structurally equal output. */
    @Test
    public fun repeatDeterminism_sameInputsProduceStructurallyEqualOutput() {
        val subject = createSubject()
        subject.samples.forEach { sample ->
            val expected = subject.projectSnapshot(sample)
            val actual = subject.projectSnapshot(sample)
            assertEquals(expected, actual, "repeat determinism: ${sample.name}")
        }
    }

    /** Two independent `project(project(base, args), args)` replay traces are equivalent. */
    @Test
    public fun doubleApplication_independentReplayTracesAreEquivalent() {
        val subject = createSubject()
        subject.samples.forEach { sample ->
            val expected = subject.doubleApplicationTrace(sample)
            val actual = subject.doubleApplicationTrace(sample)
            assertEquals(expected, actual, "double-application replay: ${sample.name}")
        }
    }

    /** Calls made before a projection do not change its output for the same fresh input pair. */
    @Test
    public fun invocationCount_doesNotAffectProjection() {
        val subject = createSubject()
        subject.samples.forEach { sample ->
            val expected = subject.projectSnapshot(sample)
            repeat(3) {
                subject.samples.forEach(subject::projectSnapshot)
            }
            val actual = subject.projectSnapshot(sample)
            assertEquals(expected, actual, "invocation-count independence: ${sample.name}")
        }
    }

    /** Named ambient-state changes do not change output for the same fresh input pair. */
    @Test
    public fun ambientState_doesNotAffectProjection() {
        val subject = createSubject()
        subject.ambientProbes.forEach { probe ->
            subject.samples.forEach { sample ->
                try {
                    probe.enterBaseline()
                    val expected = subject.projectSnapshot(sample)
                    probe.enterChanged()
                    val actual = subject.projectSnapshot(sample)
                    assertEquals(
                        expected,
                        actual,
                        "ambient-state independence: ${probe.name}/${sample.name}",
                    )
                } finally {
                    probe.restore()
                }
            }
        }
    }
}

private sealed interface ProjectionSnapshot<out S : Any> {
    data object Declined : ProjectionSnapshot<Nothing>

    data object Absent : ProjectionSnapshot<Nothing>

    data class Present<S : Any>(val value: S) : ProjectionSnapshot<S>
}

private data class DoubleApplicationTrace<S : Any>(
    val first: ProjectionSnapshot<S>,
    val second: ProjectionSnapshot<S>,
)

private fun <K : StoreKey, V : Any, A : Any, S : Any>
    MutatorPuritySubject<K, V, A, S>.projectSnapshot(
        sample: MutatorPuritySample<V, A>,
    ): ProjectionSnapshot<S> = normalize(checkedProject(sample.newBase(), sample.newArgs()))

private fun <K : StoreKey, V : Any, A : Any, S : Any>
    MutatorPuritySubject<K, V, A, S>.doubleApplicationTrace(
        sample: MutatorPuritySample<V, A>,
    ): DoubleApplicationTrace<S> {
    val base = sample.newBase()
    val firstRaw = checkedProject(base, sample.newArgs())
    val secondRaw = checkedProject(firstRaw ?: base, sample.newArgs())
    return DoubleApplicationTrace(
        first = normalize(firstRaw),
        second = normalize(secondRaw),
    )
}

private fun <K : StoreKey, V : Any, A : Any, S : Any>
    MutatorPuritySubject<K, V, A, S>.checkedProject(
        base: MutationPresence<V>,
        args: A,
    ): MutationPresence<V>? {
    val baseBefore = normalize(base)
    val argsBefore = snapshotArgs(args)
    val result = project(base, args)
    assertEquals(baseBefore, normalize(base), "projector mutated its base input")
    assertEquals(argsBefore, snapshotArgs(args), "projector mutated its args input")
    return result
}

private fun <K : StoreKey, V : Any, A : Any, S : Any>
    MutatorPuritySubject<K, V, A, S>.normalize(
        presence: MutationPresence<V>?,
    ): ProjectionSnapshot<S> =
    when (presence) {
        null -> ProjectionSnapshot.Declined
        MutationPresence.Absent -> ProjectionSnapshot.Absent
        is MutationPresence.Present -> ProjectionSnapshot.Present(snapshotValue(presence.value))
    }
