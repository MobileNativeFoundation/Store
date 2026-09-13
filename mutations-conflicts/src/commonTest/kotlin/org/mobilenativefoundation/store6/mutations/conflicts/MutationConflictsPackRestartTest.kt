@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.conflicts

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.mutations.MutationCodec
import org.mobilenativefoundation.store6.mutations.MutationConflictBuilder
import org.mobilenativefoundation.store6.mutations.MutationStore
import org.mobilenativefoundation.store6.mutations.MutatorRef
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.testing.TestWallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class MutationConflictsPackRestartTest {

    @Test
    fun restart_serverWins_matchesUninterruptedOutcome() = runTest {
        val configurer: MutationConflictBuilder<ConflictsPackKey, String>.() -> Unit = {
            serverWins()
        }
        val control =
            runStringRestartCase(
                keyId = "restart-server-wins",
                configurer = configurer,
                seed = "server",
                mutateValue = "mine",
                reseed = null,
                restart = false,
            )
        val restarted =
            runStringRestartCase(
                keyId = "restart-server-wins",
                configurer = configurer,
                seed = "server",
                mutateValue = "mine",
                reseed = null,
                restart = true,
            )
        assertEquals(
            RestartFingerprint(
                phase = MutationExecutionPhase.RETIRED,
                generations = listOf(1),
                generationTwo = null,
                generationIdempotencyKeys = control.generationIdempotencyKeys,
                pushCount = 1,
            ),
            control,
        )
        assertEquals(control, restarted)
    }

    @Test
    fun restart_clientWins_matchesUninterruptedOutcome() = runTest {
        val configurer: MutationConflictBuilder<ConflictsPackKey, String>.() -> Unit = {
            clientWins()
        }
        val control =
            runStringRestartCase(
                keyId = "restart-client-wins",
                configurer = configurer,
                seed = "server-one",
                mutateValue = "mine",
                reseed = "server-two",
                restart = false,
            )
        val restarted =
            runStringRestartCase(
                keyId = "restart-client-wins",
                configurer = configurer,
                seed = "server-one",
                mutateValue = "mine",
                reseed = "server-two",
                restart = true,
            )
        assertEquals(
            RestartFingerprint(
                phase = MutationExecutionPhase.RETIRED,
                generations = listOf(1, 2),
                generationTwo = "server-two" to "mine",
                generationIdempotencyKeys = control.generationIdempotencyKeys,
                pushCount = 2,
            ),
            control,
        )
        assertEquals(control, restarted)
    }

    @Test
    fun restart_lastWriteWins_matchesUninterruptedOutcome() = runTest {
        val configurer: MutationConflictBuilder<ConflictsPackKey, Stamped>.() -> Unit = {
            lastWriteWins { it.writtenAtEpochMillis }
        }
        val mine = Stamped("mine", 300L)
        val control =
            runStampedRestartCase(
                keyId = "restart-last-write-wins",
                configurer = configurer,
                seed = Stamped("base", 100L),
                mutateValue = mine,
                reseed = Stamped("theirs", 200L),
                restart = false,
            )
        val restarted =
            runStampedRestartCase(
                keyId = "restart-last-write-wins",
                configurer = configurer,
                seed = Stamped("base", 100L),
                mutateValue = mine,
                reseed = Stamped("theirs", 200L),
                restart = true,
            )
        assertEquals(
            RestartFingerprint(
                phase = MutationExecutionPhase.RETIRED,
                generations = listOf(1, 2),
                generationTwo = Stamped("theirs", 200L) to mine,
                generationIdempotencyKeys = control.generationIdempotencyKeys,
                pushCount = 2,
            ),
            control,
        )
        assertEquals(control, restarted)
    }

    @Test
    fun restart_threeWay_matchesUninterruptedOutcome() = runTest {
        val configurer: MutationConflictBuilder<ConflictsPackKey, Stamped>.() -> Unit = {
            threeWayMerge { _, mine, theirs ->
                Stamped(
                    mine.text + "+" + theirs.text,
                    maxOf(mine.writtenAtEpochMillis, theirs.writtenAtEpochMillis),
                )
            }
        }
        val control =
            runStampedRestartCase(
                keyId = "restart-three-way",
                configurer = configurer,
                seed = Stamped("base", 100L),
                mutateValue = Stamped("mine", 300L),
                reseed = Stamped("theirs", 200L),
                restart = false,
            )
        val restarted =
            runStampedRestartCase(
                keyId = "restart-three-way",
                configurer = configurer,
                seed = Stamped("base", 100L),
                mutateValue = Stamped("mine", 300L),
                reseed = Stamped("theirs", 200L),
                restart = true,
            )
        val merged = Stamped("mine+theirs", 300L)
        assertEquals(
            RestartFingerprint(
                phase = MutationExecutionPhase.RETIRED,
                generations = listOf(1, 2),
                generationTwo = Stamped("theirs", 200L) to merged,
                generationIdempotencyKeys = control.generationIdempotencyKeys,
                pushCount = 2,
            ),
            control,
        )
        assertEquals(control, restarted)
    }

    @Test
    fun restart_fields_matchesUninterruptedOutcome() = runTest {
        val configurer: MutationConflictBuilder<ConflictsPackKey, Stamped>.() -> Unit = {
            mergeFields {
                field(Stamped::text, { value, text -> value.copy(text = text) })
                field(
                    Stamped::writtenAtEpochMillis,
                    { value, writtenAt -> value.copy(writtenAtEpochMillis = writtenAt) },
                    combine = { _, mine, theirs -> maxOf(mine, theirs) },
                )
            }
        }
        val control =
            runStampedRestartCase(
                keyId = "restart-fields",
                configurer = configurer,
                seed = Stamped("base", 100L),
                mutateValue = Stamped("mine", 300L),
                reseed = Stamped("theirs", 200L),
                restart = false,
            )
        val restarted =
            runStampedRestartCase(
                keyId = "restart-fields",
                configurer = configurer,
                seed = Stamped("base", 100L),
                mutateValue = Stamped("mine", 300L),
                reseed = Stamped("theirs", 200L),
                restart = true,
            )
        // Same field-by-field trace as mergeFields_mergedValuePushedOnRetry: text keeps the
        // default-THEIRS contested resolution, writtenAtEpochMillis combines via maxOf.
        val merged = Stamped("theirs", 300L)
        assertEquals(
            RestartFingerprint(
                phase = MutationExecutionPhase.RETIRED,
                generations = listOf(1, 2),
                generationTwo = Stamped("theirs", 200L) to merged,
                generationIdempotencyKeys = control.generationIdempotencyKeys,
                pushCount = 2,
            ),
            control,
        )
        assertEquals(control, restarted)
    }
}

private data class RestartFingerprint<V>(
    val phase: MutationExecutionPhase,
    val generations: List<Int>,
    val generationTwo: Pair<V, V>?,
    val generationIdempotencyKeys: List<String>,
    val pushCount: Int,
)

private data class RestartJournalState(
    val execution: MutationExecutionRecord,
    val attempts: List<MutationAttemptRecord>,
)

private suspend fun MutationJournalStorage.restartJournalState(
    clientId: String,
    mutationId: String,
): RestartJournalState =
    transaction { transaction ->
        val intent = transaction.intents(clientId).single { it.mutationId == mutationId }
        RestartJournalState(
            execution =
                transaction.executions(clientId).single {
                    it.clientSequence == intent.clientSequence
                },
            attempts =
                transaction.attempts(clientId).filter {
                    it.clientSequence == intent.clientSequence
                },
        )
    }

private suspend fun runStringRestartCase(
    keyId: String,
    configurer: MutationConflictBuilder<ConflictsPackKey, String>.() -> Unit,
    seed: String,
    mutateValue: String,
    reseed: String?,
    restart: Boolean,
): RestartFingerprint<String> =
    runRestartCase(
        openStore = { storage, server, clock, policy ->
            openStringStore(storage, server, clock, policy)
        },
        configurer = configurer,
        codec = ConflictsPackStringCodec,
        mutator = stringUpsert,
        keyId = keyId,
        seed = seed,
        mutateValue = mutateValue,
        reseed = reseed,
        restart = restart,
    )

private suspend fun runStampedRestartCase(
    keyId: String,
    configurer: MutationConflictBuilder<ConflictsPackKey, Stamped>.() -> Unit,
    seed: Stamped,
    mutateValue: Stamped,
    reseed: Stamped?,
    restart: Boolean,
): RestartFingerprint<Stamped> =
    runRestartCase(
        openStore = { storage, server, clock, policy ->
            openStampedStore(storage, server, clock, policy)
        },
        configurer = configurer,
        codec = ConflictsPackStampedCodec,
        mutator = stampedUpsert,
        keyId = keyId,
        seed = seed,
        mutateValue = mutateValue,
        reseed = reseed,
        restart = restart,
    )

private suspend fun <V : Any> runRestartCase(
    openStore: (
        MutationJournalStorage,
        ConflictsPackBackend<V>,
        TestWallClock,
        MutationConflictBuilder<ConflictsPackKey, V>.() -> Unit,
    ) -> MutationStore<ConflictsPackKey, V>,
    configurer: MutationConflictBuilder<ConflictsPackKey, V>.() -> Unit,
    codec: MutationCodec<V>,
    mutator: MutatorRef<ConflictsPackKey, V, V>,
    keyId: String,
    seed: V,
    mutateValue: V,
    reseed: V?,
    restart: Boolean,
): RestartFingerprint<V> {
    val storage = InMemoryMutationJournalStorage()
    val server = ConflictsPackBackend<V>()
    val clock = TestWallClock()
    val key = ConflictsPackKey(keyId)
    var store: MutationStore<ConflictsPackKey, V>? = openStore(storage, server, clock, configurer)
    try {
        val opened = checkNotNull(store)
        server.seed(key, seed)
        opened.get(key, Freshness.MustBeFresh)
        val mutationId = opened.mutate(key, mutator, mutateValue)
        val acknowledge = server.pushBehavior
        server.pushBehavior = { push ->
            if (push.generation == 1) {
                throw conflictException(ConflictsPackMeta(10L, "restart-g1"))
            }
            acknowledge(push)
        }

        opened.drain(key)
        if (reseed != null) {
            server.seed(key, reseed)
        }

        val resumed: MutationStore<ConflictsPackKey, V>
        if (restart) {
            opened.close()
            store = null
            val clientId = capturedClientId(server, storage)
            val receipt = storage.restartJournalState(clientId, mutationId)
            assertEquals(MutationExecutionPhase.REFRESH_REQUIRED, receipt.execution.phase)
            resumed = openStore(storage, server, clock, configurer)
            store = resumed
        } else {
            resumed = opened
        }

        clock.advanceBy(2.seconds)
        resumed.drain(key)

        val clientId = capturedClientId(server, storage)
        val state = storage.restartJournalState(clientId, mutationId)
        val attempts = state.attempts.sortedBy { it.generation }
        val generationTwo =
            attempts.singleOrNull { it.generation == 2 }?.let { attempt ->
                codec.decode(1, assertNotNull(attempt.baseBlob)) to
                    codec.decode(1, assertNotNull(attempt.mineBlob))
            }
        return RestartFingerprint(
            phase = state.execution.phase,
            generations = attempts.map { it.generation },
            generationTwo = generationTwo,
            generationIdempotencyKeys = attempts.map { it.generationIdempotencyKey },
            pushCount = server.receivedPushes.size,
        )
    } finally {
        store?.close()
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
