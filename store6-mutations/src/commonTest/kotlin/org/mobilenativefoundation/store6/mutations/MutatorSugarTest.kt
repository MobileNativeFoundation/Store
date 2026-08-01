@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutatorSugarTest {
    // T4.2 bullet: the generic registration owns apply, decline, and delete outcomes distinctly.
    @Test
    fun genericMutator_appliesDeclinesAndDeletesDistinctly() = runTest {
        lateinit var generic: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                generic =
                    mutator(
                        id = "generic",
                        version = 1,
                        codec = SugarRecordingCodec(),
                        stales = { _, _ -> emptyStaleSet() },
                    ) { base, args ->
                        when {
                            args == "decline" -> null
                            args == "delete" -> MutationPresence.Absent
                            base is MutationPresence.Present ->
                                MutationPresence.Present(base.value + args)
                            else -> MutationPresence.Present(args)
                        }
                    }
            }
        val registration = registry.registrations.getValue(generic.id)

        val applied = registration.project(MutationPresence.Present("base"), "-applied")
        assertEquals("base-applied", assertIs<MutationPresence.Present<String>>(applied).value)
        assertNull(registration.project(MutationPresence.Present("base"), "decline"))
        assertSame(
            MutationPresence.Absent,
            assertNotNull(registration.project(MutationPresence.Present("base"), "delete")),
        )
    }

    // R1-06.
    @Test
    fun update_desugarsToGenericProjector() = runTest {
        lateinit var rename: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                rename =
                    update(
                        id = "rename",
                        version = 2,
                        codec = SugarRecordingCodec(),
                        stales = { _, _ -> emptyStaleSet() },
                    ) { base, suffix -> base + suffix }
            }
        val registration = registry.registrations.getValue(rename.id)

        // The sugar registered an ordinary durable mutator under the generic projector shape.
        assertEquals("rename", registration.id)
        assertEquals(2, registration.argsVersion)
        val projected = registration.project(MutationPresence.Present("base"), "-renamed")
        assertEquals("base-renamed", assertIs<MutationPresence.Present<String>>(projected).value)
    }

    // R1-06.
    @Test
    fun updateOverAbsent_declinesWithoutAttempt() = runTest {
        lateinit var rename: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                rename =
                    update(
                        id = "rename",
                        version = 1,
                        codec = SugarRecordingCodec(),
                        stales = { _, _ -> emptyStaleSet() },
                    ) { base, suffix -> base + suffix }
            }
        val registration = registry.registrations.getValue(rename.id)

        // Null is the decline signal (D13): the declined head never becomes an attempt and never
        // means deletion. Engine-level no-attempt scheduling is exercised by the drain tests.
        assertNull(registration.project(MutationPresence.Absent, "-renamed"))
    }

    // R1-06.
    @Test
    fun create_ignoresConfirmedBase() = runTest {
        lateinit var draft: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                draft =
                    create(
                        id = "draft",
                        version = 1,
                        codec = SugarRecordingCodec(),
                        stales = { _, _ -> emptyStaleSet() },
                    ) { title -> "created:$title" }
            }
        val registration = registry.registrations.getValue(draft.id)

        val overPresent = registration.project(MutationPresence.Present("resident"), "note")
        val overAbsent = registration.project(MutationPresence.Absent, "note")

        assertEquals("created:note", assertIs<MutationPresence.Present<String>>(overPresent).value)
        assertEquals("created:note", assertIs<MutationPresence.Present<String>>(overAbsent).value)
    }

    // R1-06.
    @Test
    fun delete_usesFixedUnitCodecAndProducesDrainableAbsent() = runTest {
        lateinit var remove: MutatorRef<MutationsTestKey, String, Unit>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                remove = delete("remove") { _, _ -> emptyStaleSet() }
            }
        val registration = registry.registrations.getValue(remove.id)

        // Delete always applies Absent: a non-null projection result is drainable, never a
        // decline, over both present and absent confirmed bases.
        assertSame(
            MutationPresence.Absent,
            assertNotNull(registration.project(MutationPresence.Present("resident"), Unit)),
        )
        assertSame(
            MutationPresence.Absent,
            assertNotNull(registration.project(MutationPresence.Absent, Unit)),
        )
        // The registration owns the module's fixed Unit codec at version 1.
        assertEquals(1, registration.argsVersion)
        assertTrue(registration.encodeArgs(Unit).isEmpty())
    }

    // R1-06.
    @Test
    fun upsert_receivesExplicitPresence() = runTest {
        val receivedBases = mutableListOf<MutationPresence<String>>()
        lateinit var put: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                put =
                    upsert(
                        id = "put",
                        version = 1,
                        codec = SugarRecordingCodec(),
                        stales = { _, _ -> emptyStaleSet() },
                    ) { base, value ->
                        receivedBases += base
                        MutationPresence.Present(value)
                    }
            }
        val registration = registry.registrations.getValue(put.id)

        val overAbsent = registration.project(MutationPresence.Absent, "first")
        val residentBase = MutationPresence.Present("resident")
        val overPresent = registration.project(residentBase, "second")

        // The projector received the explicit presence values, not nullable V.
        assertEquals(2, receivedBases.size)
        assertSame(MutationPresence.Absent, receivedBases[0])
        assertSame<MutationPresence<String>>(residentBase, receivedBases[1])
        // Upsert cannot decline: both results are presences.
        assertEquals("first", assertIs<MutationPresence.Present<String>>(overAbsent).value)
        assertEquals("second", assertIs<MutationPresence.Present<String>>(overPresent).value)
    }

    // R1-04.
    @Test
    fun argsCodec_receivesRegisteredVersionAndDefensiveCopy() = runTest {
        val codec = SugarRecordingCodec()
        lateinit var append: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                append =
                    mutator(
                        id = "append",
                        version = 3,
                        codec = codec,
                        stales = { _, _ -> emptyStaleSet() },
                    ) { _, args -> MutationPresence.Present(args) }
            }
        val registration = registry.registrations.getValue(append.id)

        // Encode boundary: retained bytes are a copy of the codec's returned array.
        val stored = registration.encodeArgs("payload")
        val returnedByCodec = assertNotNull(codec.lastEncodeResult)
        assertNotSame(returnedByCodec, stored)
        returnedByCodec.fill(0)
        assertContentEquals("payload".encodeToByteArray(), stored)

        // Decode boundary: the codec receives the registered version and a fresh copy.
        val decoded = registration.decodeArgs(registration.argsVersion, stored)
        assertEquals("payload", decoded)
        assertEquals(3, codec.lastDecodeVersion)
        val receivedByCodec = assertNotNull(codec.lastDecodeBytes)
        assertNotSame(stored, receivedByCodec)
        receivedByCodec.fill(0)
        assertContentEquals("payload".encodeToByteArray(), stored)
    }

    // R1-04.
    @Test
    fun delete_usesModuleUnitCodecV1AndZeroBytes_withoutCallerCodec() = runTest {
        // Compile-level: the delete sugar's signature accepts neither a version nor a codec.
        lateinit var remove: MutatorRef<MutationsTestKey, String, Unit>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                remove = delete("remove") { _, _ -> emptyStaleSet() }
            }
        val registration = registry.registrations.getValue(remove.id)

        // Fixed module-owned representation: version 1, exactly zero bytes.
        assertEquals(1, registration.argsVersion)
        val encoded = registration.encodeArgs(Unit)
        assertContentEquals(ByteArray(0), encoded)
        assertEquals(Unit, registration.decodeArgs(1, ByteArray(0)))

        // Any other durable pair is a codec violation for Issue 022 to normalize as CODEC.
        assertFailsWith<IllegalArgumentException> {
            registration.decodeArgs(2, ByteArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            registration.decodeArgs(1, byteArrayOf(1))
        }
    }
}

private class SugarRecordingCodec : MutationCodec<String> {
    var lastEncodeResult: ByteArray? = null
    var lastDecodeVersion: Int? = null
    var lastDecodeBytes: ByteArray? = null

    override fun encode(value: String): ByteArray =
        value.encodeToByteArray().also { lastEncodeResult = it }

    override fun decode(
        version: Int,
        bytes: ByteArray,
    ): String {
        lastDecodeVersion = version
        lastDecodeBytes = bytes
        return bytes.decodeToString()
    }
}

private fun emptyStaleSet(): StaleSet<MutationsTestKey> =
    StaleSet(keys = emptySet(), namespaces = emptySet())

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
