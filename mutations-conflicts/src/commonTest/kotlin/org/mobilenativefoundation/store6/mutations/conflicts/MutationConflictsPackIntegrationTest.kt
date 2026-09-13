@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.conflicts

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationConflictResolution
import org.mobilenativefoundation.store6.mutations.MutationFailureKind
import org.mobilenativefoundation.store6.mutations.MutationPendingState
import org.mobilenativefoundation.store6.mutations.MutationPresence
import org.mobilenativefoundation.store6.mutations.mutationStore
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectDisposition
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.testing.TestWallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutationConflictsPackIntegrationTest {

    @Test
    fun clientWins_conflictRetriesGenerationTwoWithRecapturedBase() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val server = ConflictsPackBackend<String>()
        val clock = TestWallClock()
        val store = openStringStore(storage, server, clock) { clientWins() }
        val key = ConflictsPackKey("client-wins-recapture")
        try {
            server.seed(key, "server-one")
            store.get(key, Freshness.MustBeFresh)
            val mutationId = store.mutate(key, stringUpsert, "mine")
            val acknowledge = server.pushBehavior
            server.pushBehavior = { push ->
                if (push.generation == 1) {
                    throw conflictException(ConflictsPackMeta(10L, "conflict-g1"))
                }
                acknowledge(push)
            }

            store.drain(key)
            server.seed(key, "server-two")
            clock.advanceBy(2.seconds)
            store.drain(key)

            val clientId = capturedClientId(server, storage)
            val state = storage.integrationState(clientId, mutationId)
            val generationOne = state.attempts.single { it.generation == 1 }
            val generationTwo = state.attempts.single { it.generation == 2 }
            assertEquals(
                "server-two",
                ConflictsPackStringCodec.decode(1, assertNotNull(generationTwo.baseBlob)),
            )
            assertEquals(
                "mine",
                ConflictsPackStringCodec.decode(1, assertNotNull(generationTwo.mineBlob)),
            )
            assertNotEquals(
                generationOne.generationIdempotencyKey,
                generationTwo.generationIdempotencyKey,
            )
            assertEquals(2, server.receivedPushes.size)
            assertEquals(2, server.receivedPushes[1].generation)
            assertEquals(MutationExecutionPhase.RETIRED, state.execution.phase)
        } finally {
            store.close()
        }
    }

    @Test
    fun serverWinsPolicy_retiresWithoutSecondPushAndSkipsEffects() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val server = ConflictsPackBackend<String>()
        val clock = TestWallClock()
        val store = openStringStore(storage, server, clock) { serverWins() }
        val key = ConflictsPackKey("server-wins-effects")
        try {
            server.seed(key, "server")
            store.get(key, Freshness.MustBeFresh)
            val mutationId = store.mutate(key, stringUpsert, "mine")
            server.pushBehavior = {
                throw conflictException(ConflictsPackMeta(20L, "server-wins"))
            }

            store.drain(key)
            clock.advanceBy(2.seconds)
            store.drain(key)

            val clientId = capturedClientId(server, storage)
            val state = storage.integrationState(clientId, mutationId)
            assertEquals(1, server.receivedPushes.size)
            assertEquals(MutationExecutionPhase.RETIRED, state.execution.phase)
            assertEquals(MutationEffectDisposition.SKIPPED, state.effects.single().disposition)
        } finally {
            store.close()
        }
    }

    @Test
    fun lastWriteWins_newerMineWinsThroughRetry() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val server = ConflictsPackBackend<Stamped>()
        val clock = TestWallClock()
        val store =
            openStampedStore(storage, server, clock) {
                lastWriteWins { it.writtenAtEpochMillis }
            }
        val key = ConflictsPackKey("last-write-newer-mine")
        try {
            server.seed(key, Stamped("base", 100L))
            store.get(key, Freshness.MustBeFresh)
            val mine = Stamped("mine", 300L)
            val mutationId = store.mutate(key, stampedUpsert, mine)
            val acknowledge = server.pushBehavior
            server.pushBehavior = { push ->
                if (push.generation == 1) {
                    throw conflictException(ConflictsPackMeta(30L, "last-write-g1"))
                }
                acknowledge(push)
            }

            store.drain(key)
            server.seed(key, Stamped("theirs", 200L))
            clock.advanceBy(2.seconds)
            store.drain(key)

            val clientId = capturedClientId(server, storage)
            val state = storage.integrationState(clientId, mutationId)
            val generationTwo = state.attempts.single { it.generation == 2 }
            assertEquals(
                mine,
                ConflictsPackStampedCodec.decode(1, assertNotNull(generationTwo.mineBlob)),
            )
            assertEquals(
                mine,
                assertIs<MutationPresence.Present<Stamped>>(server.receivedPushes[1].mine).value,
            )
            assertEquals(2, server.receivedPushes.size)
            assertEquals(MutationExecutionPhase.RETIRED, state.execution.phase)
        } finally {
            store.close()
        }
    }

    @Test
    fun lastWriteWins_staleMineRetiresToServerState() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val server = ConflictsPackBackend<Stamped>()
        val clock = TestWallClock()
        val store =
            openStampedStore(storage, server, clock) {
                lastWriteWins { it.writtenAtEpochMillis }
            }
        val key = ConflictsPackKey("last-write-stale-mine")
        try {
            server.seed(key, Stamped("base", 100L))
            store.get(key, Freshness.MustBeFresh)
            val mutationId = store.mutate(key, stampedUpsert, Stamped("mine", 150L))
            server.pushBehavior = {
                throw conflictException(ConflictsPackMeta(40L, "last-write-stale"))
            }

            store.drain(key)
            server.seed(key, Stamped("theirs", 200L))
            clock.advanceBy(2.seconds)
            store.drain(key)

            val clientId = capturedClientId(server, storage)
            val state = storage.integrationState(clientId, mutationId)
            assertEquals(1, server.receivedPushes.size)
            assertEquals(MutationExecutionPhase.RETIRED, state.execution.phase)
        } finally {
            store.close()
        }
    }

    @Test
    fun lastWriteWins_localDeleteWithMineBias_pushesDeletion() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val server = ConflictsPackBackend<Stamped>()
        val clock = TestWallClock()
        val store =
            openStampedStore(storage, server, clock) {
                lastWriteWins(onMineAbsent = MutationConflictBias.MINE) {
                    it.writtenAtEpochMillis
                }
            }
        val key = ConflictsPackKey("last-write-delete-mine")
        try {
            server.seed(key, Stamped("server", 100L))
            store.get(key, Freshness.MustBeFresh)
            val mutationId = store.mutate(key, stampedDelete, Unit)
            val acknowledge = server.pushBehavior
            server.pushBehavior = { push ->
                if (push.generation == 1) {
                    throw conflictException(ConflictsPackMeta(50L, "delete-mine"))
                }
                acknowledge(push)
            }

            store.drain(key)
            clock.advanceBy(2.seconds)
            store.drain(key)

            val clientId = capturedClientId(server, storage)
            val state = storage.integrationState(clientId, mutationId)
            assertEquals(2, server.receivedPushes.size)
            assertSame(MutationPresence.Absent, server.receivedPushes[1].mine)
            assertEquals(MutationExecutionPhase.RETIRED, state.execution.phase)
        } finally {
            store.close()
        }
    }

    @Test
    fun lastWriteWins_localDeleteWithTheirsBias_retires() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val server = ConflictsPackBackend<Stamped>()
        val clock = TestWallClock()
        val store =
            openStampedStore(storage, server, clock) {
                lastWriteWins(onMineAbsent = MutationConflictBias.THEIRS) {
                    it.writtenAtEpochMillis
                }
            }
        val key = ConflictsPackKey("last-write-delete-theirs")
        try {
            server.seed(key, Stamped("server", 100L))
            store.get(key, Freshness.MustBeFresh)
            val mutationId = store.mutate(key, stampedDelete, Unit)
            server.pushBehavior = {
                throw conflictException(ConflictsPackMeta(60L, "delete-theirs"))
            }

            store.drain(key)
            clock.advanceBy(2.seconds)
            store.drain(key)

            val clientId = capturedClientId(server, storage)
            val state = storage.integrationState(clientId, mutationId)
            assertEquals(1, server.receivedPushes.size)
            assertEquals(MutationExecutionPhase.RETIRED, state.execution.phase)
        } finally {
            store.close()
        }
    }

    @Test
    fun threeWay_mergedValuePushedOnRetry() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val server = ConflictsPackBackend<Stamped>()
        val clock = TestWallClock()
        val store =
            openStampedStore(storage, server, clock) {
                threeWayMerge { _, mine, theirs ->
                    Stamped(
                        mine.text + "+" + theirs.text,
                        maxOf(mine.writtenAtEpochMillis, theirs.writtenAtEpochMillis),
                    )
                }
            }
        val key = ConflictsPackKey("three-way-merged")
        try {
            server.seed(key, Stamped("base", 100L))
            store.get(key, Freshness.MustBeFresh)
            val mutationId = store.mutate(key, stampedUpsert, Stamped("mine", 300L))
            val acknowledge = server.pushBehavior
            server.pushBehavior = { push ->
                if (push.generation == 1) {
                    throw conflictException(ConflictsPackMeta(70L, "three-way"))
                }
                acknowledge(push)
            }

            store.drain(key)
            server.seed(key, Stamped("theirs", 200L))
            clock.advanceBy(2.seconds)
            store.drain(key)

            val merged = Stamped("mine+theirs", 300L)
            val clientId = capturedClientId(server, storage)
            val state = storage.integrationState(clientId, mutationId)
            val generationTwo = state.attempts.single { it.generation == 2 }
            assertEquals(
                merged,
                ConflictsPackStampedCodec.decode(1, assertNotNull(generationTwo.mineBlob)),
            )
            assertEquals(
                merged,
                assertIs<MutationPresence.Present<Stamped>>(server.receivedPushes[1].mine).value,
            )
            assertEquals(2, server.receivedPushes.size)
            assertEquals(MutationExecutionPhase.RETIRED, state.execution.phase)
        } finally {
            store.close()
        }
    }

    @Test
    fun mergeFields_mergedValuePushedOnRetry() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val server = ConflictsPackBackend<Stamped>()
        val clock = TestWallClock()
        val store =
            openStampedStore(storage, server, clock) {
                mergeFields {
                    field(Stamped::text, { value, text -> value.copy(text = text) })
                    field(
                        Stamped::writtenAtEpochMillis,
                        { value, writtenAt -> value.copy(writtenAtEpochMillis = writtenAt) },
                        combine = { _, mine, theirs -> maxOf(mine, theirs) },
                    )
                }
            }
        val key = ConflictsPackKey("fields-merged")
        try {
            server.seed(key, Stamped("base", 100L))
            store.get(key, Freshness.MustBeFresh)
            val mutationId = store.mutate(key, stampedUpsert, Stamped("mine", 300L))
            val acknowledge = server.pushBehavior
            server.pushBehavior = { push ->
                if (push.generation == 1) {
                    throw conflictException(ConflictsPackMeta(95L, "merge-fields"))
                }
                acknowledge(push)
            }

            store.drain(key)
            server.seed(key, Stamped("theirs", 200L))
            clock.advanceBy(2.seconds)
            store.drain(key)

            // text is contested (mine="mine" != base="base" != theirs="theirs") with no combiner
            // registered, so the default THEIRS bias keeps the canvas's initial "theirs" text.
            // writtenAtEpochMillis is contested (300 != 100 != 200) with a maxOf combiner, so it
            // resolves to 300.
            val merged = Stamped("theirs", 300L)
            val clientId = capturedClientId(server, storage)
            val state = storage.integrationState(clientId, mutationId)
            val generationTwo = state.attempts.single { it.generation == 2 }
            assertEquals(
                merged,
                ConflictsPackStampedCodec.decode(1, assertNotNull(generationTwo.mineBlob)),
            )
            assertEquals(
                merged,
                assertIs<MutationPresence.Present<Stamped>>(server.receivedPushes[1].mine).value,
            )
            assertEquals(2, server.receivedPushes.size)
            assertEquals(MutationExecutionPhase.RETIRED, state.execution.phase)
        } finally {
            store.close()
        }
    }

    @Test
    fun unchangedConflictBound_parksClientWinsOnThirdIdenticalReceipt() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val server = ConflictsPackBackend<String>()
        val clock = TestWallClock()
        val store = openStringStore(storage, server, clock) { clientWins() }
        val key = ConflictsPackKey("unchanged-conflict-bound")
        try {
            server.seed(key, "server")
            store.get(key, Freshness.MustBeFresh)
            val mutationId = store.mutate(key, stringUpsert, "mine")
            server.pushBehavior = {
                throw conflictException(ConflictsPackMeta(10L, "same"))
            }

            repeat(3) { round ->
                store.drain(key)
                if (round < 2) {
                    clock.advanceBy(2.seconds)
                }
            }

            val clientId = capturedClientId(server, storage)
            val state = storage.integrationState(clientId, mutationId)
            assertEquals(listOf(1, 2, 3), server.receivedPushes.map { it.generation })
            assertEquals(MutationExecutionPhase.PARKED, state.execution.phase)
            val deadLetter = store.deadLetters().single { it.mutationId == mutationId }
            assertEquals(MutationFailureKind.CONFLICT, deadLetter.failure.kind)
            assertEquals("conflict-unchanged-bound", deadLetter.failure.detail)
        } finally {
            store.close()
        }
    }

    @Test
    fun cannedPolicyComposesWithCallerPrecondition() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val server = ConflictsPackBackend<String>()
        val clock = TestWallClock()
        val store =
            openStringStore(storage, server, clock) {
                clientWins()
                precondition { candidate ->
                    ConflictsPackMeta(
                        1_000L + candidate.generation,
                        "selector-g${candidate.generation}",
                    )
                }
            }
        val key = ConflictsPackKey("precondition-composition")
        try {
            server.seed(key, "server-one")
            store.get(key, Freshness.MustBeFresh)
            val mutationId = store.mutate(key, stringUpsert, "mine")
            val acknowledge = server.pushBehavior
            server.pushBehavior = { push ->
                if (push.generation == 1) {
                    throw conflictException(ConflictsPackMeta(80L, "precondition-g1"))
                }
                acknowledge(push)
            }

            store.drain(key)
            server.seed(key, "server-two")
            clock.advanceBy(2.seconds)
            store.drain(key)

            val clientId = capturedClientId(server, storage)
            val state = storage.integrationState(clientId, mutationId)
            val generationOne = state.attempts.single { it.generation == 1 }
            val generationTwo = state.attempts.single { it.generation == 2 }
            assertEquals(1_001L, generationOne.preconditionWrittenAt)
            assertEquals("selector-g1", generationOne.preconditionEtag)
            assertEquals(1_002L, generationTwo.preconditionWrittenAt)
            assertEquals("selector-g2", generationTwo.preconditionEtag)
            assertEquals(MutationExecutionPhase.RETIRED, state.execution.phase)
        } finally {
            store.close()
        }
    }

    @Test
    fun codecRejectingRetryValuePropagatesAndStaysRefreshing() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val server = ConflictsPackBackend<String>()
        val clock = TestWallClock()
        val rejectingCodec =
            object : MutationCodec<String> {
                override fun encode(value: String): ByteArray {
                    if (value == REJECT_SENTINEL) {
                        error(REJECT_MESSAGE)
                    }
                    return ConflictsPackStringCodec.encode(value)
                }

                override fun decode(
                    version: Int,
                    bytes: ByteArray,
                ): String = ConflictsPackStringCodec.decode(version, bytes)
            }
        val store =
            mutationStore(
                registry = stringRegistry,
                server = server,
                keyResolver = ConflictsPackKeyResolver,
                valueCodecVersion = 1,
                valueCodec = rejectingCodec,
            ) {
                fetcherOfResult { server.loadResult(it) }
                journalStorage(storage)
                wallClock(clock)
                conflicts {
                    merge { _, _, _ ->
                        MutationConflictResolution.Retry(
                            MutationPresence.Present(REJECT_SENTINEL),
                        )
                    }
                }
            }
        val key = ConflictsPackKey("codec-rejecting-retry")
        try {
            server.seed(key, "server")
            store.get(key, Freshness.MustBeFresh)
            val mutationId = store.mutate(key, stringUpsert, "mine")
            server.pushBehavior = {
                throw conflictException(ConflictsPackMeta(90L, "codec-retry"))
            }

            store.drain(key)
            clock.advanceBy(2.seconds)
            val failure = assertFails { store.drain(key) }

            assertTrue(
                generateSequence(failure) { it.cause }.any {
                    REJECT_MESSAGE in it.message.orEmpty()
                },
            )
            val pending = store.pendingWrites().single { it.mutationId == mutationId }
            assertEquals(MutationPendingState.REFRESHING, pending.state)
        } finally {
            store.close()
        }
    }
}

private const val REJECT_SENTINEL: String = "codec-reject-sentinel"
private const val REJECT_MESSAGE: String = "retry sentinel value rejected by codec"

private data class IntegrationJournalState(
    val execution: MutationExecutionRecord,
    val attempts: List<MutationAttemptRecord>,
    val effects: List<MutationEffectRecord>,
)

private suspend fun MutationJournalStorage.integrationState(
    clientId: String,
    mutationId: String,
): IntegrationJournalState =
    transaction { transaction ->
        val intent = transaction.intents(clientId).single { it.mutationId == mutationId }
        IntegrationJournalState(
            execution =
                transaction.executions(clientId).single {
                    it.clientSequence == intent.clientSequence
                },
            attempts =
                transaction.attempts(clientId).filter {
                    it.clientSequence == intent.clientSequence
                },
            effects =
                transaction.effects(clientId).filter {
                    it.clientSequence == intent.clientSequence
                },
        )
    }

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
