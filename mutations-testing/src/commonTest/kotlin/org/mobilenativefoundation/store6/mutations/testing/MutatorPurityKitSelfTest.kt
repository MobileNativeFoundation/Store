@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.testing

import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationPresence
import org.mobilenativefoundation.store6.mutations.StaleSet
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class MutatorPurityKitSelfTest :
    MutatorPurityContractKit<PurityKey, String, String, String>() {
    private var ambientValue: Int = 0

    override fun createSubject(): MutatorPuritySubject<PurityKey, String, String, String> =
        puritySubject(
            ambientProbes = listOf(ambientProbe { value -> ambientValue = value }),
        ) { base, args ->
            val current = (base as? MutationPresence.Present)?.value.orEmpty()
            when (args) {
                "decline" -> null
                "delete" -> MutationPresence.Absent
                else -> MutationPresence.Present(current + args)
            }
        }

    @Test
    fun counterCapturedProjector_isRejected() {
        var invocationCount = 0
        var ambient = 0
        val impure =
            object : MutatorPurityContractKit<PurityKey, String, String, String>() {
                override fun createSubject(): MutatorPuritySubject<PurityKey, String, String, String> =
                    puritySubject(
                        ambientProbes = listOf(ambientProbe { value -> ambient = value }),
                    ) { base, args ->
                        invocationCount += 1
                        val current = (base as? MutationPresence.Present)?.value.orEmpty()
                        MutationPresence.Present("$current$args#$invocationCount")
                    }
            }

        val failure =
            assertFailsWith<AssertionError> {
                impure.repeatDeterminism_sameInputsProduceStructurallyEqualOutput()
            }
        assertContains(failure.message.orEmpty(), "repeat determinism")
    }

    @Test
    fun ambientCapturedProjector_isRejected() {
        var ambient = 0
        val impure =
            object : MutatorPurityContractKit<PurityKey, String, String, String>() {
                override fun createSubject(): MutatorPuritySubject<PurityKey, String, String, String> =
                    puritySubject(
                        ambientProbes = listOf(ambientProbe { value -> ambient = value }),
                    ) { base, args ->
                        val current = (base as? MutationPresence.Present)?.value.orEmpty()
                        MutationPresence.Present("$current$args@$ambient")
                    }
            }

        val failure =
            assertFailsWith<AssertionError> {
                impure.ambientState_doesNotAffectProjection()
            }
        assertContains(failure.message.orEmpty(), "ambient-state independence")
    }

    @Test
    fun baseMutatingProjector_isRejected() {
        var ambient = 0
        val impure =
            object : MutatorPurityContractKit<PurityKey, StringBuilder, String, String>() {
                override fun createSubject():
                    MutatorPuritySubject<PurityKey, StringBuilder, String, String> =
                    mutatorPuritySubject(
                        id = "base-mutator",
                        version = 1,
                        codec = PurityStringCodec,
                        stales = { _, _ -> StaleSet(emptySet(), emptySet()) },
                        samples =
                            listOf(
                                MutatorPuritySample(
                                    name = "mutable base",
                                    newBase = { MutationPresence.Present(StringBuilder("base")) },
                                    newArgs = { "+suffix" },
                                ),
                            ),
                        snapshotValue = { value -> value.toString() },
                        ambientProbes = listOf(ambientProbe { value -> ambient = value }),
                    ) { base, args ->
                        val mutable = (base as MutationPresence.Present).value
                        mutable.append(args)
                        MutationPresence.Present(mutable)
                    }
            }

        assertFailsWith<AssertionError> {
            impure.repeatDeterminism_sameInputsProduceStructurallyEqualOutput()
        }
    }

    @Test
    fun argsMutatingProjector_isRejected() {
        var ambient = 0
        val impure =
            object : MutatorPurityContractKit<PurityKey, String, StringBuilder, String>() {
                override fun createSubject():
                    MutatorPuritySubject<PurityKey, String, StringBuilder, String> =
                    mutatorPuritySubject(
                        id = "args-mutator",
                        version = 1,
                        codec = PurityStringBuilderCodec,
                        stales = { _, _ -> StaleSet(emptySet(), emptySet()) },
                        samples =
                            listOf(
                                MutatorPuritySample(
                                    name = "mutable args",
                                    newBase = { MutationPresence.Present("base") },
                                    newArgs = { StringBuilder("+suffix") },
                                ),
                            ),
                        snapshotValue = { value -> value },
                        ambientProbes = listOf(ambientProbe { value -> ambient = value }),
                    ) { base, args ->
                        args.append("!")
                        val current = (base as MutationPresence.Present).value
                        MutationPresence.Present(current + args)
                    }
            }

        assertFailsWith<AssertionError> {
            impure.repeatDeterminism_sameInputsProduceStructurallyEqualOutput()
        }
    }
}

private fun puritySubject(
    ambientProbes: List<MutatorAmbientProbe>,
    project: (MutationPresence<String>, String) -> MutationPresence<String>?,
): MutatorPuritySubject<PurityKey, String, String, String> =
    mutatorPuritySubject(
        id = "append-decline-delete",
        version = 1,
        codec = PurityStringCodec,
        stales = { _, _ -> StaleSet(emptySet(), emptySet()) },
        samples = puritySamples(),
        snapshotValue = { value -> value },
        ambientProbes = ambientProbes,
        project = project,
    )

private fun ambientProbe(setValue: (Int) -> Unit): MutatorAmbientProbe =
    MutatorAmbientProbe(
        name = "ambient integer",
        enterBaseline = { setValue(0) },
        enterChanged = { setValue(41) },
        restore = { setValue(0) },
    )

class PurityKey(
    private val id: String,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace("purity")

    override fun canonicalId(): String = id
}

private object PurityStringCodec : MutationCodec<String> {
    override fun encode(value: String): ByteArray = value.encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): String = bytes.decodeToString()
}

private object PurityStringBuilderCodec : MutationCodec<StringBuilder> {
    override fun encode(value: StringBuilder): ByteArray = value.toString().encodeToByteArray()

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): StringBuilder = StringBuilder(bytes.decodeToString())
}

private fun puritySamples(): List<MutatorPuritySample<String, String>> =
    listOf(
        MutatorPuritySample(
            name = "absent plus suffix",
            newBase = { MutationPresence.Absent },
            newArgs = { "+one" },
        ),
        MutatorPuritySample(
            name = "present plus suffix",
            newBase = { MutationPresence.Present("base") },
            newArgs = { "+two" },
        ),
        MutatorPuritySample(
            name = "declined update",
            newBase = { MutationPresence.Present("base") },
            newArgs = { "decline" },
        ),
        MutatorPuritySample(
            name = "delete",
            newBase = { MutationPresence.Present("base") },
            newArgs = { "delete" },
        ),
    )
