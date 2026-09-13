@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class MutationJournalTest {
    @Test
    fun append_thenPendingSnapshot_returnsIntentInOrder() = runTest {
        val journal = InMemoryMutationJournal<String>()
        val firstKey = MutationsTestKey("first")
        val secondKey = MutationsTestKey("second")

        journal.append(firstKey.identity(), JournalEntry("mutation-1", "append", "one"))
        journal.append(secondKey.identity(), JournalEntry("mutation-2", "append", "isolated"))
        journal.append(firstKey.identity(), JournalEntry("mutation-3", "append", "three"))

        assertEquals(
            listOf("mutation-1", "mutation-3"),
            journal
                .pendingSnapshot(firstKey.identity())
                .map(JournalEntry<String>::mutationId),
        )
        assertEquals(
            listOf("mutation-2"),
            journal
                .pendingSnapshot(secondKey.identity())
                .map(JournalEntry<String>::mutationId),
        )
    }

    @Test
    fun retire_removesIntent_andLeavesSiblingsPending() = runTest {
        val journal = InMemoryMutationJournal<String>()
        val key = MutationsTestKey("retire")
        journal.append(key.identity(), JournalEntry("mutation-1", "append", "one"))
        journal.append(key.identity(), JournalEntry("mutation-2", "append", "two"))
        journal.append(key.identity(), JournalEntry("mutation-3", "append", "three"))

        journal.retire(key.identity(), "mutation-2")

        assertEquals(
            listOf("mutation-1", "mutation-3"),
            journal
                .pendingSnapshot(key.identity())
                .map(JournalEntry<String>::mutationId),
        )
    }

    @Test
    fun pendingSnapshot_isNonSuspending_andReflectsLatestAppend() = runTest {
        val journal = InMemoryMutationJournal<String>()
        val key = MutationsTestKey("snapshot")
        journal.append(key.identity(), JournalEntry("mutation-1", "append", "one"))

        assertEquals(
            listOf("mutation-1"),
            readPendingFromNonSuspendingContext(journal, key.identity())
                .map(JournalEntry<String>::mutationId),
        )
    }

    @Test
    fun hostileMutator_neverEscapesProjectAll() = runTest {
        lateinit var hostile: MutatorRef<MutationsTestKey, String, Unit>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                hostile =
                    mutator(
                        id = "hostile",
                        version = 1,
                        codec = inertArgsCodec<Unit>(),
                        stales = noStales(),
                    ) { _, _ -> throw IllegalStateException("boom") }
            }
        val engine = MutationEngine(registry, echoingMutationServer())
        val key = MutationsTestKey("hostile")
        engine.mutate(key, hostile, Unit)

        assertEquals("base", engine.projectAll(key, "base"))
    }

    @Test
    fun hostileMutator_isRecordedAsPoisoned() = runTest {
        val failure = IllegalStateException("boom")
        lateinit var hostile: MutatorRef<MutationsTestKey, String, Unit>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                hostile =
                    mutator(
                        id = "hostile",
                        version = 1,
                        codec = inertArgsCodec<Unit>(),
                        stales = noStales(),
                    ) { _, _ -> throw failure }
            }
        val engine = MutationEngine(registry, echoingMutationServer())
        val key = MutationsTestKey("poison")
        val mutationId = engine.mutate(key, hostile, Unit)

        assertEquals("base", engine.projectAll(key, "base"))
        val poisoned = engine.poisoned.first()
        assertEquals(mutationId, poisoned.mutationId)
        assertEquals(hostile.id, poisoned.mutatorId)
        assertSame(failure, poisoned.failure)
    }

    @Test
    fun mutatorThrownCancellation_isAlsoContained() = runTest {
        val failure = CancellationException("cancelled by user projection")
        lateinit var cancelling: MutatorRef<MutationsTestKey, String, Unit>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                cancelling =
                    mutator(
                        id = "cancelling",
                        version = 1,
                        codec = inertArgsCodec<Unit>(),
                        stales = noStales(),
                    ) { _, _ -> throw failure }
            }
        val engine = MutationEngine(registry, echoingMutationServer())
        val key = MutationsTestKey("cancellation")
        val mutationId = engine.mutate(key, cancelling, Unit)

        assertEquals("base", engine.projectAll(key, "base"))
        val poisoned = engine.poisoned.first()
        assertEquals(mutationId, poisoned.mutationId)
        assertEquals(cancelling.id, poisoned.mutatorId)
        assertSame(failure, poisoned.failure)
    }

    @Test
    fun unknownMutatorId_isSkipped_notFatal() = runTest {
        val journal = InMemoryMutationJournal<String>()
        val registry = mutatorRegistry<MutationsTestKey, String> {}
        val engine = MutationEngine(registry, echoingMutationServer(), journal)
        val key = MutationsTestKey("unknown")
        journal.append(key.identity(), JournalEntry("mutation-1", "missing", Unit))

        assertEquals("base", engine.projectAll(key, "base"))
    }

    @Test
    fun foreignMutatorRef_withAbsentId_isRejectedBeforeJournalAppend() = runTest {
        lateinit var foreign: MutatorRef<MutationsTestKey, String, Unit>
        mutatorRegistry<MutationsTestKey, String> {
            foreign =
                mutator(
                    id = "foreign",
                    version = 1,
                    codec = inertArgsCodec<Unit>(),
                    stales = noStales(),
                ) { base, _ -> base }
        }
        val journal = InMemoryMutationJournal<String>()
        val engine =
            MutationEngine(
                registry = mutatorRegistry<MutationsTestKey, String> {},
                server = echoingMutationServer(),
                journal = journal,
            )
        val key = MutationsTestKey("foreign-absent")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                engine.mutate(key, foreign, Unit)
            }

        assertEquals(
            "MutatorRef 'foreign' belongs to a different MutatorRegistry.",
            failure.message,
        )
        assertEquals(emptyList(), journal.pendingSnapshot(key.identity()))
    }

    @Test
    fun foreignMutatorRef_withCollidingId_isRejectedBeforeJournalAppend() = runTest {
        lateinit var foreign: MutatorRef<MutationsTestKey, String, Int>
        mutatorRegistry<MutationsTestKey, String> {
            foreign =
                mutator(
                    id = "shared",
                    version = 1,
                    codec = inertArgsCodec<Int>(),
                    stales = noStales(),
                ) { base, amount ->
                    (base as? MutationPresence.Present)?.let {
                        MutationPresence.Present(it.value + amount)
                    }
                }
        }
        val targetRegistry =
            mutatorRegistry<MutationsTestKey, String> {
                mutator(
                    id = "shared",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { base, suffix ->
                    (base as? MutationPresence.Present)?.let {
                        MutationPresence.Present(it.value + suffix)
                    }
                }
            }
        val journal = InMemoryMutationJournal<String>()
        val engine = MutationEngine(targetRegistry, echoingMutationServer(), journal)
        val key = MutationsTestKey("foreign-collision")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                engine.mutate(key, foreign, 1)
            }

        assertEquals(
            "MutatorRef 'shared' belongs to a different MutatorRegistry.",
            failure.message,
        )
        assertEquals(emptyList(), journal.pendingSnapshot(key.identity()))
    }

    @Test
    fun duplicateMutatorId_withDifferentArgType_isRejectedBeforeOverwrite() = runTest {
        val builder = MutatorRegistryBuilder<MutationsTestKey, String>()
        val original =
            builder.mutator(
                id = "shared",
                version = 1,
                codec = inertArgsCodec<Int>(),
                stales = noStales(),
            ) { base, amount ->
                MutationPresence.Present(
                    ((base as? MutationPresence.Present)?.value).orEmpty() + amount,
                )
            }

        val failure =
            assertFailsWith<IllegalArgumentException> {
                builder.mutator(
                    id = "shared",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { base, suffix ->
                    MutationPresence.Present(
                        ((base as? MutationPresence.Present)?.value).orEmpty() + suffix,
                    )
                }
            }

        assertEquals("Mutator id 'shared' is already registered.", failure.message)
        val engine = MutationEngine(builder.build(), echoingMutationServer())
        val key = MutationsTestKey("duplicate")
        engine.mutate(key, original, 7)
        assertEquals("base7", engine.projectAll(key, "base"))
    }

    @Test
    fun escapedBuilder_cannotRegisterAfterRegistryBuild() = runTest {
        lateinit var escaped: MutatorRegistryBuilder<MutationsTestKey, String>
        mutatorRegistry<MutationsTestKey, String> {
            escaped = this
        }

        val registrationFailure =
            assertFailsWith<IllegalArgumentException> {
                escaped.mutator(
                    id = "late",
                    version = 1,
                    codec = inertArgsCodec<Unit>(),
                    stales = noStales(),
                ) { base, _ -> base }
            }

        assertEquals(
            "MutatorRegistryBuilder is already built.",
            registrationFailure.message,
        )
        val buildFailure =
            assertFailsWith<IllegalArgumentException> {
                escaped.build()
            }
        assertEquals("MutatorRegistryBuilder is already built.", buildFailure.message)
    }
}

private fun <V : Any> readPendingFromNonSuspendingContext(
    journal: MutationJournal<V>,
    key: KeyIdentity,
): List<JournalEntry<V>> = journal.pendingSnapshot(key)

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
