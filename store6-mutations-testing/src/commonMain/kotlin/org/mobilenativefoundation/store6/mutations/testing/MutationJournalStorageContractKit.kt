@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.testing

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.mutations.MutationFailureKind
import org.mobilenativefoundation.store6.mutations.MutationPresenceState
import org.mobilenativefoundation.store6.mutations.storage.MutationAckRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationAliasState
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectDisposition
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectKind
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationFailureRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationIntentRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalTransaction
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyAliasRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyTombstoneRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationTombstoneState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Conformance kit for durable mutation-journal storage implementations.
 *
 * Extend this class in a consumer test source set and run the inherited tests on every supported
 * target. [reopenStorage] is the restart boundary: an in-memory implementation returns the same
 * instance (the in-memory restart analogue), while a persistent implementation returns a new
 * adapter over the same durable store.
 *
 * The kit enforces the named phase rules rather than a false total ordering. In particular,
 * `INFLIGHT -> READY` is legal after a transport failure, and `REFRESH_REQUIRED` may advance to a
 * new immutable generation before returning to `READY`. `RETIRED` and `PARKED` are terminal;
 * `ACKED` and `EFFECTS_PENDING` never regress to a pre-ack phase.
 *
 * All writes use one generic composable transaction door. A thrown callback commits none of its
 * operations. Ordinary pruning is limited to the persisted server-confirmed retirement prefix;
 * alias redirects and the active tombstone generation survive it.
 */
@ExperimentalStoreApi
public abstract class MutationJournalStorageContractKit : JournalStorageKillPointScenarios() {
    /** Creates a fresh storage instance for one inherited contract test. */
    public abstract override fun createStorage(): MutationJournalStorage

    /** Reopens [previous] without clearing its durable records. */
    public abstract override fun reopenStorage(previous: MutationJournalStorage): MutationJournalStorage

    @Test
    public fun clientRecord_enforcesThreeWayInvariantAndMonotonicConfirmation(): TestResult =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                client(lastAllocated = 1L, retiredThrough = 2L)
            }
            assertFailsWith<IllegalArgumentException> {
                client(lastAllocated = 2L, retiredThrough = 1L, confirmedThrough = 2L)
            }

            val storage = createStorage()
            assertFailsWith<IllegalArgumentException> {
                storage.transaction { transaction ->
                    transaction.insertClient(client(lastAllocated = 1L))
                }
            }
            storage.transaction { transaction ->
                transaction.insertClient(client())
                transaction.advanceClient(
                    client(
                        lastAllocated = 3L,
                        retiredThrough = 2L,
                    ),
                )
            }

            assertFailsWith<IllegalArgumentException> {
                storage.transaction { transaction ->
                    transaction.confirmRetiredThrough(
                        clientId = CLIENT_ID,
                        requestedThroughSequence = 1L,
                        serverConfirmedThroughSequence = 2L,
                    )
                }
            }
            storage.transaction { transaction ->
                transaction.confirmRetiredThrough(
                    clientId = CLIENT_ID,
                    requestedThroughSequence = 2L,
                    serverConfirmedThroughSequence = 1L,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                storage.transaction { transaction ->
                    transaction.advanceClient(
                        client(
                            lastAllocated = 2L,
                            retiredThrough = 2L,
                            confirmedThrough = 1L,
                        ),
                    )
                }
            }
            assertFailsWith<IllegalArgumentException> {
                storage.transaction { transaction ->
                    transaction.advanceClient(
                        client(
                            lastAllocated = 3L,
                            retiredThrough = 1L,
                            confirmedThrough = 1L,
                        ),
                    )
                }
            }
            assertFailsWith<IllegalArgumentException> {
                storage.transaction { transaction ->
                    transaction.advanceClient(
                        client(
                            lastAllocated = 3L,
                            retiredThrough = 2L,
                            confirmedThrough = 2L,
                        ),
                    )
                }
            }
            assertFailsWith<IllegalArgumentException> {
                storage.transaction { transaction ->
                    transaction.confirmRetiredThrough(
                        clientId = CLIENT_ID,
                        requestedThroughSequence = 2L,
                        serverConfirmedThroughSequence = 0L,
                    )
                }
            }

            storage.transaction { transaction ->
                transaction.confirmRetiredThrough(
                    clientId = CLIENT_ID,
                    requestedThroughSequence = 2L,
                    serverConfirmedThroughSequence = 2L,
                )
            }
            val persisted = storage.transaction { it.client(CLIENT_ID) }
            assertEquals(3L, requireNotNull(persisted).lastAllocatedSequence)
            assertEquals(2L, persisted.retiredThroughSequence)
            assertEquals(2L, persisted.serverConfirmedRetiredThroughSequence)
        }

    @Test
    public fun intent_isImmutableUniqueAndOrderedByClientSequence(): TestResult = runTest {
        val storage = createStorage()
        storage.transaction { transaction ->
            transaction.insertClient(client())
            transaction.advanceClient(client(lastAllocated = 2L))
            insertIntent(transaction, sequence = 2L, mutationId = "mutation-2")
            transaction.insertExecution(execution(sequence = 2L))
            insertIntent(transaction, sequence = 1L, mutationId = "mutation-1")
            transaction.insertExecution(execution(sequence = 1L))
        }

        val ordered = storage.transaction { it.intents(CLIENT_ID) }
        assertEquals(listOf(1L, 2L), ordered.map { it.clientSequence })
        assertEquals(listOf("mutation-1", "mutation-2"), ordered.map { it.mutationId })

        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                insertIntent(transaction, sequence = 1L, mutationId = "other-id")
            }
        }
        storage.transaction { transaction ->
            transaction.advanceClient(client(lastAllocated = 3L))
        }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                insertIntent(transaction, sequence = 3L, mutationId = "mutation-1")
            }
        }

        val afterRejectedWrites = storage.transaction { it.intents(CLIENT_ID) }
        assertEquals(2, afterRejectedWrites.size)
        assertEquals("items", afterRejectedWrites.first().namespace)
    }

    @Test
    public fun enqueue_intentAndUnpreparedExecutionCommitAtomically(): TestResult = runTest {
        val storage = createStorage()
        storage.transaction { it.insertClient(client()) }
        val failure = Rollback()

        val caught =
            assertFailsWith<Rollback> {
                storage.transaction { transaction ->
                    transaction.advanceClient(client(lastAllocated = 1L))
                    insertIntent(transaction, sequence = 1L)
                    transaction.insertExecution(execution(sequence = 1L))
                    throw failure
                }
            }
        assertSame(failure, caught)
        storage.transaction { transaction ->
            assertEquals(0L, requireNotNull(transaction.client(CLIENT_ID)).lastAllocatedSequence)
            assertTrue(transaction.intents(CLIENT_ID).isEmpty())
            assertTrue(transaction.executions(CLIENT_ID).isEmpty())
        }

        storage.transaction { transaction ->
            transaction.advanceClient(client(lastAllocated = 1L))
            insertIntent(transaction, sequence = 1L)
            transaction.insertExecution(execution(sequence = 1L))
        }
        storage.transaction { transaction ->
            assertEquals(1L, requireNotNull(transaction.client(CLIENT_ID)).lastAllocatedSequence)
            assertEquals(1, transaction.intents(CLIENT_ID).size)
            assertEquals(MutationExecutionPhase.UNPREPARED, transaction.executions(CLIENT_ID).single().phase)
        }
    }

    @Test
    public fun execution_preservesRuledLegalEdgesAndTerminalStates(): TestResult = runTest {
        assertFailsWith<IllegalArgumentException> {
            execution(
                sequence = 1L,
                attemptCount = 1,
                lastAttemptAt = 1L,
            )
        }

        val readyParkingStorage = createStorage()
        appendIntent(readyParkingStorage, sequence = 1L)
        readyParkingStorage.transaction { transaction ->
            transaction.insertAttempt(attempt(sequence = 1L, generation = 1))
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.READY,
                    generation = 1,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            readyParkingStorage.transaction { transaction ->
                val failure =
                    transaction.appendFailure(
                        clientId = CLIENT_ID,
                        clientSequence = 1L,
                        generation = 1,
                        kind = MutationFailureKind.PROTOCOL,
                        detail = "ready",
                        message = "parking before an invocation completes",
                        occurredAt = 30L,
                    )
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.PARKED,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 30L,
                        activeFailureId = failure.failureId,
                    ),
                )
            }
        }

        suspend fun inflightParkingStorage(
            withCompletedAttempt: Boolean = false,
        ): MutationJournalStorage =
            createStorage().also { candidate ->
                appendIntent(candidate, sequence = 1L)
                prepareForPush(candidate, sequence = 1L)
                if (withCompletedAttempt) {
                    candidate.transaction { transaction ->
                        transaction.advanceExecution(
                            execution(
                                sequence = 1L,
                                phase = MutationExecutionPhase.READY,
                                generation = 1,
                                attemptCount = 1,
                                lastAttemptAt = 30L,
                            ),
                        )
                        transaction.advanceExecution(
                            execution(
                                sequence = 1L,
                                phase = MutationExecutionPhase.INFLIGHT,
                                generation = 1,
                                attemptCount = 1,
                                lastAttemptAt = 30L,
                            ),
                        )
                    }
                }
            }

        suspend fun parkInflight(
            candidate: MutationJournalStorage,
            kind: MutationFailureKind,
            attemptCount: Int,
            lastAttemptAt: Long?,
        ): MutationFailureRecord =
            candidate.transaction { transaction ->
                val failure =
                    transaction.appendFailure(
                        clientId = CLIENT_ID,
                        clientSequence = 1L,
                        generation = 1,
                        kind = kind,
                        detail = "inflight",
                        message = "terminal invocation failure",
                        occurredAt = 40L,
                    )
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.PARKED,
                        generation = 1,
                        attemptCount = attemptCount,
                        lastAttemptAt = lastAttemptAt,
                        activeFailureId = failure.failureId,
                    ),
                )
                failure
            }

        listOf(MutationFailureKind.IDENTITY, MutationFailureKind.CODEC).forEach { kind ->
            val candidate = inflightParkingStorage(withCompletedAttempt = true)
            val failure =
                parkInflight(
                    candidate = candidate,
                    kind = kind,
                    attemptCount = 1,
                    lastAttemptAt = 30L,
                )
            val parked = candidate.transaction { it.executions(CLIENT_ID).single() }
            val activeFailure =
                candidate.transaction { transaction ->
                    transaction.failures(CLIENT_ID).single { it.failureId == parked.activeFailureId }
                }
            assertEquals(MutationExecutionPhase.PARKED, parked.phase)
            assertEquals(1, parked.currentGeneration)
            assertEquals(1, parked.attempt)
            assertEquals(30L, parked.lastAttemptAt)
            assertEquals(failure.failureId, parked.activeFailureId)
            assertEquals(kind, activeFailure.kind)
        }

        val changedTimestampStorage = inflightParkingStorage(withCompletedAttempt = true)
        assertFailsWith<IllegalArgumentException> {
            parkInflight(
                candidate = changedTimestampStorage,
                kind = MutationFailureKind.IDENTITY,
                attemptCount = 1,
                lastAttemptAt = 31L,
            )
        }
        assertTrue(changedTimestampStorage.transaction { it.failures(CLIENT_ID).isEmpty() })

        val unchangedTransportStorage = inflightParkingStorage()
        assertFailsWith<IllegalArgumentException> {
            parkInflight(
                candidate = unchangedTransportStorage,
                kind = MutationFailureKind.TRANSPORT,
                attemptCount = 0,
                lastAttemptAt = null,
            )
        }
        assertTrue(unchangedTransportStorage.transaction { it.failures(CLIENT_ID).isEmpty() })

        val skippedAttemptStorage = inflightParkingStorage()
        assertFailsWith<IllegalArgumentException> {
            parkInflight(
                candidate = skippedAttemptStorage,
                kind = MutationFailureKind.TRANSPORT,
                attemptCount = 2,
                lastAttemptAt = 40L,
            )
        }
        assertTrue(skippedAttemptStorage.transaction { it.failures(CLIENT_ID).isEmpty() })

        val completedParkingStorage = inflightParkingStorage()
        parkInflight(
            candidate = completedParkingStorage,
            kind = MutationFailureKind.TRANSPORT,
            attemptCount = 1,
            lastAttemptAt = 40L,
        )
        val completedParking = completedParkingStorage.transaction { it.executions(CLIENT_ID).single() }
        assertEquals(MutationExecutionPhase.PARKED, completedParking.phase)
        assertEquals(1, completedParking.attempt)
        assertEquals(40L, completedParking.lastAttemptAt)

        val storage = createStorage()
        appendIntent(storage, sequence = 1L)
        storage.transaction { transaction ->
            transaction.insertAttempt(attempt(sequence = 1L, generation = 1))
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.READY,
                    generation = 1,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.INFLIGHT,
                    generation = 1,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.READY,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 30L,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.INFLIGHT,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 30L,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.REFRESH_REQUIRED,
                    generation = 1,
                    attemptCount = 2,
                    lastAttemptAt = 40L,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.READY,
                        generation = 1,
                        attemptCount = 2,
                        lastAttemptAt = 40L,
                    ),
                )
            }
        }
        suspend fun assertGenerationContinuationRejected(
            effectiveNamespace: String,
            effectiveCanonicalId: String,
            expectedMessage: String,
        ) {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    storage.transaction { transaction ->
                        transaction.insertAttempt(
                            attempt(
                                sequence = 1L,
                                generation = 2,
                                effectiveNamespace = effectiveNamespace,
                                effectiveCanonicalId = effectiveCanonicalId,
                            ),
                        )
                        transaction.advanceExecution(
                            execution(
                                sequence = 1L,
                                phase = MutationExecutionPhase.READY,
                                generation = 2,
                            ),
                        )
                    }
                }
            assertEquals(expectedMessage, failure.message)
            storage.transaction { transaction ->
                val persisted =
                    transaction.executions(CLIENT_ID).single { it.clientSequence == 1L }
                assertEquals(MutationExecutionPhase.REFRESH_REQUIRED, persisted.phase)
                assertEquals(1, persisted.currentGeneration)
                assertEquals(
                    listOf(1),
                    transaction.attempts(CLIENT_ID)
                        .filter { it.clientSequence == 1L }
                        .map { it.generation },
                )
            }
        }

        assertGenerationContinuationRejected(
            effectiveNamespace = "items",
            effectiveCanonicalId = "retargeted",
            expectedMessage = "generation continuation must preserve exact effective identity",
        )
        assertGenerationContinuationRejected(
            effectiveNamespace = "moved-items",
            effectiveCanonicalId = "item-1",
            expectedMessage = "generation continuation must preserve exact effective identity",
        )

        appendIntent(
            storage = storage,
            sequence = 2L,
            namespace = "occupied-items",
            canonicalId = "occupied-owner",
        )
        storage.transaction { transaction ->
            transaction.insertAttempt(
                attempt(
                    sequence = 2L,
                    generation = 1,
                    effectiveNamespace = "occupied-items",
                    effectiveCanonicalId = "occupied-owner",
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 2L,
                    phase = MutationExecutionPhase.READY,
                    generation = 1,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 2L,
                    phase = MutationExecutionPhase.INFLIGHT,
                    generation = 1,
                ),
            )
        }
        assertGenerationContinuationRejected(
            effectiveNamespace = "occupied-items",
            effectiveCanonicalId = "item-1",
            expectedMessage = "namespace authority already has an owner",
        )

        storage.transaction { transaction ->
            transaction.insertAttempt(attempt(sequence = 1L, generation = 2))
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.READY,
                    generation = 2,
                ),
            )
        }

        val generationTwo =
            storage.transaction {
                it.executions(CLIENT_ID).single { execution -> execution.clientSequence == 1L }
            }
        assertEquals(MutationExecutionPhase.READY, generationTwo.phase)
        assertEquals(2, generationTwo.currentGeneration)
        val continuationAttempts =
            storage.transaction {
                it.attempts(CLIENT_ID)
                    .filter { attempt -> attempt.clientSequence == 1L }
                    .sortedBy { attempt -> attempt.generation }
            }
        assertEquals(
            listOf("items" to "item-1", "items" to "item-1"),
            continuationAttempts.map { it.effectiveNamespace to it.effectiveCanonicalId },
        )

        storage.transaction { transaction ->
            val failure =
                transaction.appendFailure(
                    clientId = CLIENT_ID,
                    clientSequence = 1L,
                    generation = 2,
                    kind = MutationFailureKind.PROTOCOL,
                    detail = "terminal",
                    message = "terminal parking",
                    occurredAt = 50L,
                )
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.PARKED,
                    generation = 2,
                    activeFailureId = failure.failureId,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.READY,
                        generation = 2,
                    ),
                )
            }
        }

        val acknowledgedStorage = createStorage()
        appendIntent(acknowledgedStorage, sequence = 1L)
        prepareForPush(acknowledgedStorage, sequence = 1L)
        acknowledgedStorage.transaction { transaction ->
            transaction.insertAck(ack(sequence = 1L, generation = 1))
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.ACKED,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 50L,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            acknowledgedStorage.transaction { transaction ->
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.READY,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 50L,
                    ),
                )
            }
        }
        acknowledgedStorage.transaction { transaction ->
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.EFFECTS_PENDING,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 50L,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            acknowledgedStorage.transaction { transaction ->
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.INFLIGHT,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 50L,
                    ),
                )
            }
        }

        val retiredStorage = createStorage()
        appendIntent(retiredStorage, sequence = 1L)
        prepareConflict(retiredStorage, sequence = 1L)
        retiredStorage.transaction { transaction ->
            transaction.advanceEffect(
                effect(
                    sequence = 1L,
                    index = 0,
                    disposition = MutationEffectDisposition.SKIPPED,
                    completedAt = 50L,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.RETIRED,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 40L,
                    retiredAt = 50L,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            retiredStorage.transaction { transaction ->
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.READY,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 40L,
                    ),
                )
            }
        }

        val namespaceOwnerStorage = createStorage()
        appendIntent(
            storage = namespaceOwnerStorage,
            sequence = 1L,
            canonicalId = "owner",
        )
        appendIntent(
            storage = namespaceOwnerStorage,
            sequence = 2L,
            canonicalId = "blocked",
        )
        appendIntent(
            storage = namespaceOwnerStorage,
            sequence = 3L,
            canonicalId = "after-retirement",
        )
        appendIntent(
            storage = namespaceOwnerStorage,
            sequence = 4L,
            namespace = "other-items",
            canonicalId = "independent",
        )
        namespaceOwnerStorage.transaction { transaction ->
            listOf(
                Triple(1L, "items", "owner"),
                Triple(2L, "items", "blocked"),
                Triple(3L, "items", "after-retirement"),
                Triple(4L, "other-items", "independent"),
            ).forEach { (sequence, namespace, canonicalId) ->
                transaction.insertAttempt(
                    attempt(
                        sequence = sequence,
                        generation = 1,
                        effectiveNamespace = namespace,
                        effectiveCanonicalId = canonicalId,
                    ),
                )
                transaction.advanceExecution(
                    execution(
                        sequence = sequence,
                        phase = MutationExecutionPhase.READY,
                        generation = 1,
                    ),
                )
            }
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.INFLIGHT,
                    generation = 1,
                ),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            namespaceOwnerStorage.transaction { transaction ->
                transaction.advanceExecution(
                    execution(
                        sequence = 2L,
                        phase = MutationExecutionPhase.INFLIGHT,
                        generation = 1,
                    ),
                )
            }
        }
        namespaceOwnerStorage.transaction { transaction ->
            val phases =
                transaction.executions(CLIENT_ID).associate { it.clientSequence to it.phase }
            assertEquals(MutationExecutionPhase.INFLIGHT, phases.getValue(1L))
            assertEquals(MutationExecutionPhase.READY, phases.getValue(2L))
        }

        namespaceOwnerStorage.transaction { transaction ->
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.READY,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 30L,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.INFLIGHT,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 30L,
                ),
            )
        }
        namespaceOwnerStorage.transaction { transaction ->
            val failure =
                transaction.appendFailure(
                    clientId = CLIENT_ID,
                    clientSequence = 1L,
                    generation = 1,
                    kind = MutationFailureKind.TRANSPORT,
                    detail = "owner-release",
                    message = "owner parked after transport",
                    occurredAt = 40L,
                )
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.PARKED,
                    generation = 1,
                    attemptCount = 2,
                    lastAttemptAt = 40L,
                    activeFailureId = failure.failureId,
                ),
            )
        }
        namespaceOwnerStorage.transaction { transaction ->
            transaction.advanceExecution(
                execution(
                    sequence = 2L,
                    phase = MutationExecutionPhase.INFLIGHT,
                    generation = 1,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 4L,
                    phase = MutationExecutionPhase.INFLIGHT,
                    generation = 1,
                ),
            )
        }
        namespaceOwnerStorage.transaction { transaction ->
            transaction.insertAck(sameIdentityAck(sequence = 2L, generation = 1))
            transaction.advanceExecution(
                execution(
                    sequence = 2L,
                    phase = MutationExecutionPhase.ACKED,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 50L,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 2L,
                    phase = MutationExecutionPhase.EFFECTS_PENDING,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 50L,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 2L,
                    phase = MutationExecutionPhase.RETIRED,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 50L,
                    retiredAt = 60L,
                ),
            )
        }
        namespaceOwnerStorage.transaction { transaction ->
            transaction.advanceExecution(
                execution(
                    sequence = 3L,
                    phase = MutationExecutionPhase.INFLIGHT,
                    generation = 1,
                ),
            )
            val phases =
                transaction.executions(CLIENT_ID).associate { it.clientSequence to it.phase }
            assertEquals(MutationExecutionPhase.PARKED, phases.getValue(1L))
            assertEquals(MutationExecutionPhase.RETIRED, phases.getValue(2L))
            assertEquals(MutationExecutionPhase.INFLIGHT, phases.getValue(3L))
            assertEquals(MutationExecutionPhase.INFLIGHT, phases.getValue(4L))
        }

        C8OwnerState.entries.forEach { ownerState ->
            val candidate = c8OwnerPredicateStorage(ownerState)
            assertFailsWith<IllegalArgumentException>(ownerState.name) {
                candidate.transaction { transaction ->
                    transaction.advanceExecution(
                        execution(
                            sequence = 2L,
                            phase = MutationExecutionPhase.INFLIGHT,
                            generation = 1,
                        ),
                    )
                }
            }
            candidate.transaction { transaction ->
                val executions = transaction.executions(CLIENT_ID).associateBy { it.clientSequence }
                assertEquals(ownerState.phase, executions.getValue(1L).phase, ownerState.name)
                assertEquals(MutationExecutionPhase.READY, executions.getValue(2L).phase, ownerState.name)
            }
        }
    }

    @Test
    public fun execution_ackedRequiresMatchingAcknowledgement(): TestResult = runTest {
        val storage = createStorage()
        appendIntent(storage, sequence = 1L)
        prepareForPush(storage, sequence = 1L)

        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.ACKED,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 50L,
                    ),
                )
            }
        }

        storage.transaction { transaction ->
            assertTrue(transaction.acks(CLIENT_ID).isEmpty())
            assertEquals(
                MutationExecutionPhase.INFLIGHT,
                transaction.executions(CLIENT_ID).single().phase,
            )
        }
    }

    @Test
    public fun persistedEnums_roundTripEveryStableName(): TestResult = runTest {
        assertEquals(
            listOf(
                "UNPREPARED",
                "READY",
                "INFLIGHT",
                "REFRESH_REQUIRED",
                "ACKED",
                "EFFECTS_PENDING",
                "PARKED",
                "RETIRED",
            ),
            MutationExecutionPhase.entries.map { it.name },
        )
        assertEquals(listOf("KEY", "NAMESPACE"), MutationEffectKind.entries.map { it.name })
        assertEquals(
            listOf("PENDING", "APPLIED", "SKIPPED"),
            MutationEffectDisposition.entries.map { it.name },
        )
        assertEquals(listOf("PENDING", "ACTIVE"), MutationAliasState.entries.map { it.name })
        assertEquals(
            listOf("PENDING", "ACTIVE", "SUPERSEDED"),
            MutationTombstoneState.entries.map { it.name },
        )
        assertEquals(
            listOf("PRESENT", "ABSENT"),
            MutationPresenceState.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "IDENTITY",
                "CODEC",
                "PROJECTION",
                "PROTOCOL",
                "CONFLICT",
                "TRANSPORT",
                "ADOPTION",
                "EFFECT",
                "PERSISTENCE",
            ),
            MutationFailureKind.entries.map { it.name },
        )

        val storage = createStorage()
        appendIntent(storage, sequence = 1L)
        storage.transaction { transaction ->
            transaction.insertEffect(effect(sequence = 1L, index = 0, kind = MutationEffectKind.KEY))
            transaction.insertEffect(
                effect(sequence = 1L, index = 1, kind = MutationEffectKind.NAMESPACE),
            )
            transaction.insertAlias(alias())
            transaction.insertTombstone(tombstone())
        }
        storage.transaction { transaction ->
            assertEquals(
                listOf("KEY", "NAMESPACE"),
                transaction.effects(CLIENT_ID).map { it.kind.name },
            )
            assertEquals("PENDING", transaction.aliases().single().state.name)
            assertEquals("PENDING", transaction.tombstones().single().state.name)
        }
    }

    @Test
    public fun attemptGeneration_isImmutableAndAppendOnly(): TestResult = runTest {
        val storage = createStorage()
        appendIntent(storage, sequence = 1L)
        storage.transaction { transaction ->
            transaction.insertAttempt(attempt(sequence = 1L, generation = 1))
            transaction.recordConflictReceipt(
                attempt(
                    sequence = 1L,
                    generation = 1,
                    conflictMetaPresent = true,
                    conflictWrittenAt = 31L,
                    conflictEtag = "conflict-etag",
                    conflictReceivedAt = 32L,
                ),
            )
            transaction.insertAttempt(attempt(sequence = 1L, generation = 2, mine = byteArrayOf(8)))
        }

        appendIntent(storage, sequence = 2L)
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.insertAttempt(
                    attempt(
                        sequence = 2L,
                        generation = 1,
                        generationIdempotencyKey = "generation-1-1",
                    ),
                )
            }
        }

        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.recordConflictReceipt(
                    attempt(
                        sequence = 1L,
                        generation = 1,
                        effectiveCanonicalId = "changed",
                        conflictMetaPresent = true,
                        conflictWrittenAt = 31L,
                        conflictEtag = "conflict-etag",
                        conflictReceivedAt = 32L,
                    ),
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.recordConflictReceipt(
                    attempt(
                        sequence = 1L,
                        generation = 1,
                        conflictMetaPresent = false,
                        conflictReceivedAt = 33L,
                    ),
                )
            }
        }
        val attempts = storage.transaction { it.attempts(CLIENT_ID) }
        assertEquals(listOf(1, 2), attempts.map { it.generation })
        assertEquals("item-1", attempts.first().effectiveCanonicalId)
        assertEquals(true, attempts.first().conflictMetaPresent)
        assertEquals(31L, attempts.first().conflictWrittenAt)
        assertEquals("conflict-etag", attempts.first().conflictEtag)
        assertEquals(32L, attempts.first().conflictReceivedAt)
        assertContentEquals(byteArrayOf(8), attempts.last().mineBlob)
    }

    @Test
    public fun ack_isWriteOnceAndMismatchDoesNotOverwrite(): TestResult = runTest {
        val storage = createStorage()
        appendIntent(storage, sequence = 1L)
        storage.transaction { transaction ->
            transaction.insertAttempt(attempt(sequence = 1L, generation = 1))
            transaction.insertAck(ack(sequence = 1L, generation = 1))
            transaction.insertAck(ack(sequence = 1L, generation = 1))
            transaction.insertAck(ack(sequence = 1L, generation = 1, receivedAt = 51L))
        }

        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.insertAck(
                    ack(
                        sequence = 1L,
                        generation = 1,
                        authoritative = byteArrayOf(9),
                    ),
                )
            }
        }
        val persisted = storage.transaction { it.acks(CLIENT_ID).single() }
        assertContentEquals(byteArrayOf(7), persisted.authoritativeBlob)
        assertEquals(50L, persisted.receivedAt)
    }

    @Test
    public fun failure_isAppendOnlyAndUtf8BoundedAtCodePointBoundary(): TestResult = runTest {
        val storage = createStorage()
        appendIntent(storage, sequence = 1L)
        val detail = "D".repeat(127) + "🙂"
        val message = "M".repeat(1_023) + "🙂"

        storage.transaction { transaction ->
            transaction.appendFailure(
                clientId = CLIENT_ID,
                clientSequence = 1L,
                generation = 0,
                kind = MutationFailureKind.CODEC,
                detail = detail,
                message = message,
                occurredAt = 10L,
            )
            transaction.appendFailure(
                clientId = CLIENT_ID,
                clientSequence = 1L,
                generation = 0,
                kind = MutationFailureKind.PERSISTENCE,
                detail = "second",
                message = "second failure",
                occurredAt = 11L,
            )
        }

        val failures = storage.transaction { it.failures(CLIENT_ID) }
        assertEquals(2, failures.size)
        assertEquals(2, failures.map { it.failureId }.toSet().size)
        assertEquals(failures.map { it.failureId }.sorted(), failures.map { it.failureId })
        val truncated = failures.single { it.occurredAt == 10L }
        assertTrue(truncated.detail.encodeToByteArray().size <= 128)
        assertTrue(truncated.message.encodeToByteArray().size <= 1_024)
        assertEquals("D".repeat(127), truncated.detail)
        assertEquals("M".repeat(1_023), truncated.message)
        assertEquals(MutationFailureKind.CODEC, truncated.kind)
    }

    @Test
    public fun effect_startsPendingAndAdvancesDispositionForwardOnly(): TestResult = runTest {
        assertFailsWith<IllegalArgumentException> {
            effect(
                sequence = 1L,
                index = 0,
                disposition = MutationEffectDisposition.PENDING,
                completedAt = 10L,
            )
        }
        val storage = createStorage()
        appendIntent(storage, sequence = 1L)
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.insertEffect(
                    effect(
                        sequence = 1L,
                        index = 0,
                        disposition = MutationEffectDisposition.APPLIED,
                        completedAt = 19L,
                    ),
                )
            }
        }
        storage.transaction { transaction ->
            transaction.insertEffect(effect(sequence = 1L, index = 0))
            transaction.advanceEffect(
                effect(
                    sequence = 1L,
                    index = 0,
                    disposition = MutationEffectDisposition.APPLIED,
                    completedAt = 20L,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.insertEffect(effect(sequence = 1L, index = 0))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.advanceEffect(effect(sequence = 1L, index = 0))
            }
        }
        val persisted = storage.transaction { it.effects(CLIENT_ID).single() }
        assertEquals(MutationEffectDisposition.APPLIED, persisted.disposition)
        assertEquals(20L, persisted.completedAt)
    }

    @Test
    public fun alias_rejectsSelfEdgeRetargetAndCycle(): TestResult = runTest {
        val storage = createStorage()
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.insertAlias(
                    alias(sourceId = "same", targetId = "same"),
                )
            }
        }

        storage.transaction { it.insertAlias(alias(sourceId = "a", targetId = "b")) }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.insertAlias(alias(sourceId = "a", targetId = "c"))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.insertAlias(alias(sourceId = "b", targetId = "a"))
            }
        }
        assertEquals(1, storage.transaction { it.aliases().size })
    }

    @Test
    public fun alias_equalDuplicateIsIdempotentAndActivationIsMonotonic(): TestResult = runTest {
        val storage = createStorage()
        storage.transaction { transaction ->
            transaction.insertAlias(alias())
            transaction.insertAlias(alias())
            transaction.advanceAlias(alias(state = MutationAliasState.ACTIVE, activatedAt = 20L))
            transaction.insertAlias(alias(sequence = 2L))
        }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction -> transaction.advanceAlias(alias()) }
        }
        val persisted = storage.transaction { it.aliases().single() }
        assertEquals(MutationAliasState.ACTIVE, persisted.state)
        assertEquals(20L, persisted.activatedAt)
    }

    @Test
    public fun tombstone_allowsAtMostOnePendingAndOneActiveGeneration(): TestResult = runTest {
        val storage = createStorage()
        storage.transaction { it.insertTombstone(tombstone(sequence = 1L)) }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.insertTombstone(tombstone(sequence = 2L))
            }
        }

        storage.transaction { transaction ->
            transaction.advanceTombstone(
                tombstone(
                    sequence = 1L,
                    state = MutationTombstoneState.ACTIVE,
                    activatedAt = 20L,
                ),
            )
            transaction.insertTombstone(tombstone(sequence = 2L, createdAt = 21L))
        }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.insertTombstone(tombstone(sequence = 3L, createdAt = 22L))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.advanceTombstone(
                    tombstone(
                        sequence = 2L,
                        createdAt = 21L,
                        state = MutationTombstoneState.ACTIVE,
                        activatedAt = 22L,
                    ),
                )
            }
        }

        val generations = storage.transaction { it.tombstones() }
        assertEquals(2, generations.size)
        assertEquals(1, generations.count { it.state == MutationTombstoneState.ACTIVE })
        assertEquals(1, generations.count { it.state == MutationTombstoneState.PENDING })
    }

    @Test
    public fun tombstone_generationAdvancesForwardOnly(): TestResult = runTest {
        val storage = createStorage()
        storage.transaction { transaction ->
            transaction.insertTombstone(tombstone(sequence = 1L))
            transaction.advanceTombstone(
                tombstone(
                    sequence = 1L,
                    state = MutationTombstoneState.ACTIVE,
                    activatedAt = 20L,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.advanceTombstone(
                    tombstone(
                        sequence = 1L,
                        state = MutationTombstoneState.SUPERSEDED,
                        activatedAt = 999L,
                        supersededBySequence = 9L,
                        supersededAt = 30L,
                    ),
                )
            }
        }
        val stillActive = storage.transaction { it.tombstones().single() }
        assertEquals(MutationTombstoneState.ACTIVE, stillActive.state)
        assertEquals(20L, stillActive.activatedAt)

        storage.transaction { transaction ->
            transaction.advanceTombstone(
                tombstone(
                    sequence = 1L,
                    state = MutationTombstoneState.SUPERSEDED,
                    activatedAt = 20L,
                    supersededBySequence = 9L,
                    supersededAt = 30L,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                transaction.advanceTombstone(
                    tombstone(
                        sequence = 1L,
                        state = MutationTombstoneState.ACTIVE,
                        activatedAt = 20L,
                    ),
                )
            }
        }
        val persisted = storage.transaction { it.tombstones().single() }
        assertEquals(MutationTombstoneState.SUPERSEDED, persisted.state)
        assertEquals(9L, persisted.supersededBySequence)

        val guardedLowerSuccessorStorage = createStorage()
        guardedLowerSuccessorStorage.transaction { transaction ->
            transaction.insertTombstone(
                tombstone(
                    sequence = 3L,
                    canonicalId = "guarded-lower-successor",
                ),
            )
            transaction.advanceTombstone(
                tombstone(
                    sequence = 3L,
                    canonicalId = "guarded-lower-successor",
                    state = MutationTombstoneState.ACTIVE,
                    activatedAt = 40L,
                ),
            )
        }
        val unguardedLowerSuccessor =
            tombstone(
                sequence = 3L,
                canonicalId = "guarded-lower-successor",
                state = MutationTombstoneState.SUPERSEDED,
                activatedAt = 40L,
                supersededByClientId = CLIENT_ID,
                supersededBySequence = 1L,
                supersededAt = 50L,
            )
        assertFailsWith<IllegalArgumentException> {
            guardedLowerSuccessorStorage.transaction { transaction ->
                transaction.advanceTombstone(unguardedLowerSuccessor)
            }
        }
        val guardedPredecessor = guardedLowerSuccessorStorage.transaction { it.tombstones().single() }
        assertEquals(MutationTombstoneState.ACTIVE, guardedPredecessor.state)
        assertEquals(40L, guardedPredecessor.activatedAt)
        assertNull(guardedPredecessor.supersededByClientId)
        assertNull(guardedPredecessor.supersededBySequence)
        assertNull(guardedPredecessor.supersededAt)
    }

    @Test
    public fun prune_stopsAtServerConfirmedPrefix(): TestResult = runTest {
        val storage = createStorage()
        appendIntent(storage, sequence = 1L)
        appendIntent(storage, sequence = 2L)
        appendIntent(storage, sequence = 3L)
        retireForPrune(storage, sequence = 1L, retiredThrough = 1L)
        retireForPrune(storage, sequence = 2L, retiredThrough = 2L)
        storage.transaction { transaction ->
            transaction.insertTombstone(
                tombstone(sequence = 1L, canonicalId = "obsolete"),
            )
            transaction.advanceTombstone(
                tombstone(
                    sequence = 1L,
                    canonicalId = "obsolete",
                    state = MutationTombstoneState.ACTIVE,
                    activatedAt = 60L,
                ),
            )
            transaction.advanceTombstone(
                tombstone(
                    sequence = 1L,
                    canonicalId = "obsolete",
                    state = MutationTombstoneState.SUPERSEDED,
                    activatedAt = 60L,
                    supersededBySequence = 2L,
                    supersededAt = 61L,
                ),
            )
            transaction.confirmRetiredThrough(
                clientId = CLIENT_ID,
                requestedThroughSequence = 2L,
                serverConfirmedThroughSequence = 2L,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            storage.transaction { it.prune(CLIENT_ID, serverConfirmedRetiredThroughSequence = 3L) }
        }
        storage.transaction { it.prune(CLIENT_ID, serverConfirmedRetiredThroughSequence = 2L) }

        assertFailsWith<IllegalArgumentException> {
            storage.transaction { transaction ->
                insertIntent(
                    transaction = transaction,
                    sequence = 1L,
                    mutationId = "mutation-reused-after-prune",
                )
            }
        }

        storage.transaction { transaction ->
            assertEquals(listOf(3L), transaction.intents(CLIENT_ID).map { it.clientSequence })
            assertEquals(listOf(3L), transaction.executions(CLIENT_ID).map { it.clientSequence })
            assertTrue(transaction.attempts(CLIENT_ID).isEmpty())
            assertTrue(transaction.acks(CLIENT_ID).isEmpty())
            assertTrue(transaction.failures(CLIENT_ID).isEmpty())
            assertTrue(transaction.effects(CLIENT_ID).isEmpty())
            assertTrue(transaction.tombstones().none { it.canonicalId == "obsolete" })
            assertEquals(2L, requireNotNull(transaction.client(CLIENT_ID)).serverConfirmedRetiredThroughSequence)
        }
    }

    @Test
    public fun prune_preservesAliasesAndActiveTombstoneGeneration(): TestResult = runTest {
        val storage = createStorage()
        appendIntent(storage, sequence = 1L)
        storage.transaction { transaction ->
            transaction.insertAlias(alias())
            transaction.advanceAlias(
                alias(state = MutationAliasState.ACTIVE, activatedAt = 10L),
            )
            transaction.insertTombstone(
                tombstone(sequence = 1L, canonicalId = "protected-active"),
            )
            transaction.advanceTombstone(
                tombstone(
                    sequence = 1L,
                    canonicalId = "protected-active",
                    state = MutationTombstoneState.ACTIVE,
                    activatedAt = 11L,
                ),
            )
            transaction.insertTombstone(
                tombstone(sequence = 1L, canonicalId = "protected-pending"),
            )
        }
        retireForPrune(storage, sequence = 1L, retiredThrough = 1L)
        storage.transaction { transaction ->
            transaction.confirmRetiredThrough(
                clientId = CLIENT_ID,
                requestedThroughSequence = 1L,
                serverConfirmedThroughSequence = 1L,
            )
            transaction.prune(CLIENT_ID, serverConfirmedRetiredThroughSequence = 1L)
        }

        storage.transaction { transaction ->
            assertTrue(transaction.intents(CLIENT_ID).isEmpty())
            assertTrue(transaction.executions(CLIENT_ID).isEmpty())
            assertEquals(MutationAliasState.ACTIVE, transaction.aliases().single().state)
            assertEquals(
                setOf(MutationTombstoneState.ACTIVE, MutationTombstoneState.PENDING),
                transaction.tombstones().map { it.state }.toSet(),
            )
        }
    }

    @Test
    public fun blobs_areCopiedOnEntryAndDelivery(): TestResult = runTest {
        val storage = createStorage()
        val args = byteArrayOf(1, 2, 3)
        val base = byteArrayOf(4, 5)
        val mine = byteArrayOf(6)
        val authoritative = byteArrayOf(7, 8)
        storage.transaction { transaction ->
            transaction.insertClient(client())
            transaction.advanceClient(client(lastAllocated = 1L))
            insertIntent(transaction, sequence = 1L, args = args)
            transaction.insertExecution(execution(sequence = 1L))
            transaction.insertAttempt(
                attempt(sequence = 1L, generation = 1, base = base, mine = mine),
            )
            transaction.insertAck(
                ack(sequence = 1L, generation = 1, authoritative = authoritative),
            )
        }
        args[0] = 99
        base[0] = 99
        mine[0] = 99
        authoritative[0] = 99

        storage.transaction { transaction ->
            val intentBlob = transaction.intents(CLIENT_ID).single().argsBlob
            val persistedAttempt = transaction.attempts(CLIENT_ID).single()
            val ackBlob = requireNotNull(transaction.acks(CLIENT_ID).single().authoritativeBlob)
            assertContentEquals(byteArrayOf(1, 2, 3), intentBlob)
            assertContentEquals(byteArrayOf(4, 5), persistedAttempt.baseBlob)
            assertContentEquals(byteArrayOf(6), persistedAttempt.mineBlob)
            assertContentEquals(byteArrayOf(7, 8), ackBlob)
            intentBlob[0] = 88
            requireNotNull(persistedAttempt.baseBlob)[0] = 88
            requireNotNull(persistedAttempt.mineBlob)[0] = 88
            ackBlob[0] = 88
        }
        storage.transaction { transaction ->
            assertContentEquals(byteArrayOf(1, 2, 3), transaction.intents(CLIENT_ID).single().argsBlob)
            assertContentEquals(byteArrayOf(4, 5), transaction.attempts(CLIENT_ID).single().baseBlob)
            assertContentEquals(byteArrayOf(6), transaction.attempts(CLIENT_ID).single().mineBlob)
            assertContentEquals(byteArrayOf(7, 8), transaction.acks(CLIENT_ID).single().authoritativeBlob)
        }
    }

    @Test
    public fun reopen_roundTripsClientRecord(): TestResult = runTest {
        val storage = createStorage()
        storage.transaction { transaction ->
            transaction.insertClient(client())
            transaction.advanceClient(
                client(lastAllocated = 4L, retiredThrough = 3L),
            )
            transaction.confirmRetiredThrough(
                clientId = CLIENT_ID,
                requestedThroughSequence = 2L,
                serverConfirmedThroughSequence = 2L,
            )
        }

        val reopened = reopenStorage(storage)
        val persisted = reopened.transaction { it.client(CLIENT_ID) }
        assertNotNull(persisted)
        assertEquals(1, persisted.recordVersion)
        assertEquals(4L, persisted.lastAllocatedSequence)
        assertEquals(3L, persisted.retiredThroughSequence)
        assertEquals(2L, persisted.serverConfirmedRetiredThroughSequence)
        assertEquals(1L, persisted.createdAt)
    }

    @Test
    public fun reopen_roundTripsIntentAndExecutionRecords(): TestResult = runTest {
        val storage = createStorage()
        val inserted = appendIntent(storage, sequence = 1L)
        storage.transaction { transaction ->
            transaction.insertAttempt(attempt(sequence = 1L, generation = 1))
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.READY,
                    generation = 1,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.INFLIGHT,
                    generation = 1,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.READY,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 30L,
                ),
            )
        }

        val reopened = reopenStorage(storage)
        reopened.transaction { transaction ->
            val persistedIntent = transaction.intents(CLIENT_ID).single()
            assertEquals(inserted.rowId, persistedIntent.rowId)
            assertEquals(1, persistedIntent.recordVersion)
            assertEquals(CLIENT_ID, persistedIntent.clientId)
            assertEquals(1L, persistedIntent.clientSequence)
            assertEquals("mutation-1", persistedIntent.mutationId)
            assertEquals("items", persistedIntent.namespace)
            assertEquals("item-1", persistedIntent.canonicalId)
            assertEquals("mutator", persistedIntent.mutatorId)
            assertEquals(1, persistedIntent.mutatorVersion)
            assertContentEquals(byteArrayOf(1, 2, 3), persistedIntent.argsBlob)
            assertEquals("root-1", persistedIntent.idempotencyRoot)
            assertEquals(11L, persistedIntent.createdAt)
            val persistedExecution = transaction.executions(CLIENT_ID).single()
            assertEquals(CLIENT_ID, persistedExecution.clientId)
            assertEquals(1L, persistedExecution.clientSequence)
            assertEquals(MutationExecutionPhase.READY, persistedExecution.phase)
            assertEquals(1, persistedExecution.currentGeneration)
            assertEquals(1, persistedExecution.attempt)
            assertEquals(30L, persistedExecution.lastAttemptAt)
            assertNull(persistedExecution.activeFailureId)
            assertNull(persistedExecution.retiredAt)
        }
    }

    @Test
    public fun reopen_roundTripsAttemptAndAckRecords(): TestResult = runTest {
        val storage = createStorage()
        appendIntent(storage, sequence = 1L)
        storage.transaction { transaction ->
            transaction.insertAttempt(
                attempt(
                    sequence = 1L,
                    generation = 1,
                    preconditionMetaPresent = true,
                    preconditionWrittenAt = 19L,
                    preconditionEtag = "before-etag",
                    advertisedRetiredThroughSequence = 0L,
                ),
            )
            transaction.recordConflictReceipt(
                attempt(
                    sequence = 1L,
                    generation = 1,
                    preconditionMetaPresent = true,
                    preconditionWrittenAt = 19L,
                    preconditionEtag = "before-etag",
                    advertisedRetiredThroughSequence = 0L,
                    conflictMetaPresent = true,
                    conflictWrittenAt = 29L,
                    conflictEtag = "conflict-etag",
                    conflictReceivedAt = 30L,
                ),
            )
            transaction.insertAck(ack(sequence = 1L, generation = 1))
        }

        val reopened = reopenStorage(storage)
        reopened.transaction { transaction ->
            val persistedAttempt = transaction.attempts(CLIENT_ID).single()
            assertEquals(CLIENT_ID, persistedAttempt.clientId)
            assertEquals(1L, persistedAttempt.clientSequence)
            assertEquals(1, persistedAttempt.generation)
            assertEquals("items", persistedAttempt.effectiveNamespace)
            assertEquals("item-1", persistedAttempt.effectiveCanonicalId)
            assertEquals(1, persistedAttempt.valueCodecVersion)
            assertEquals(MutationPresenceState.PRESENT, persistedAttempt.basePresence)
            assertEquals(MutationPresenceState.PRESENT, persistedAttempt.minePresence)
            assertEquals("generation-1-1", persistedAttempt.generationIdempotencyKey)
            assertContentEquals(byteArrayOf(4), persistedAttempt.baseBlob)
            assertContentEquals(byteArrayOf(6), persistedAttempt.mineBlob)
            assertTrue(persistedAttempt.preconditionMetaPresent)
            assertEquals(19L, persistedAttempt.preconditionWrittenAt)
            assertEquals("before-etag", persistedAttempt.preconditionEtag)
            assertEquals(0L, persistedAttempt.advertisedRetiredThroughSequence)
            assertEquals(21L, persistedAttempt.preparedAt)
            assertEquals(true, persistedAttempt.conflictMetaPresent)
            assertEquals(29L, persistedAttempt.conflictWrittenAt)
            assertEquals("conflict-etag", persistedAttempt.conflictEtag)
            assertEquals(30L, persistedAttempt.conflictReceivedAt)
            val persistedAck = transaction.acks(CLIENT_ID).single()
            assertEquals(CLIENT_ID, persistedAck.clientId)
            assertEquals(1L, persistedAck.clientSequence)
            assertEquals(1, persistedAck.generation)
            assertEquals(MutationPresenceState.PRESENT, persistedAck.authoritativePresence)
            assertContentEquals(byteArrayOf(7), persistedAck.authoritativeBlob)
            assertEquals(1, persistedAck.valueCodecVersion)
            assertEquals("etag", persistedAck.etag)
            assertEquals("items", persistedAck.canonicalTargetNamespace)
            assertEquals("canonical-1", persistedAck.canonicalTargetId)
            assertEquals(50L, persistedAck.receivedAt)
        }
    }

    @Test
    public fun reopen_roundTripsFailureAndEffectRecords(): TestResult = runTest {
        val storage = createStorage()
        appendIntent(storage, sequence = 1L)
        val insertedFailure =
            storage.transaction { transaction ->
                val failure = transaction.appendFailure(
                    clientId = CLIENT_ID,
                    clientSequence = 1L,
                    generation = 0,
                    kind = MutationFailureKind.TRANSPORT,
                    detail = "offline",
                    message = "network unavailable",
                    occurredAt = 12L,
                )
                transaction.insertEffect(effect(sequence = 1L, index = 0))
                failure
            }

        val reopened = reopenStorage(storage)
        reopened.transaction { transaction ->
            val failure = transaction.failures(CLIENT_ID).single()
            assertEquals(insertedFailure.failureId, failure.failureId)
            assertEquals(CLIENT_ID, failure.clientId)
            assertEquals(1L, failure.clientSequence)
            assertEquals(0, failure.generation)
            assertEquals(MutationFailureKind.TRANSPORT, failure.kind)
            assertEquals("offline", failure.detail)
            assertEquals("network unavailable", failure.message)
            assertEquals(12L, failure.occurredAt)
            val persistedEffect = transaction.effects(CLIENT_ID).single()
            assertEquals(CLIENT_ID, persistedEffect.clientId)
            assertEquals(1L, persistedEffect.clientSequence)
            assertEquals(0, persistedEffect.effectIndex)
            assertEquals(MutationEffectKind.KEY, persistedEffect.kind)
            assertEquals("effects", persistedEffect.namespace)
            assertEquals("target-0", persistedEffect.canonicalId)
            assertEquals(20L, persistedEffect.createdAt)
            assertEquals(MutationEffectDisposition.PENDING, persistedEffect.disposition)
            assertNull(persistedEffect.completedAt)
        }
    }

    @Test
    public fun reopen_roundTripsAliasAndTombstoneRecords(): TestResult = runTest {
        val storage = createStorage()
        storage.transaction { transaction ->
            transaction.insertAlias(alias())
            transaction.insertTombstone(tombstone())
        }

        val reopened = reopenStorage(storage)
        reopened.transaction { transaction ->
            val persistedAlias = transaction.aliases().single()
            assertEquals("items", persistedAlias.sourceNamespace)
            assertEquals("item-1", persistedAlias.sourceCanonicalId)
            assertEquals("items", persistedAlias.targetNamespace)
            assertEquals("canonical-1", persistedAlias.targetCanonicalId)
            assertEquals(MutationAliasState.PENDING, persistedAlias.state)
            assertEquals(CLIENT_ID, persistedAlias.createdByClientId)
            assertEquals(1L, persistedAlias.createdBySequence)
            assertEquals(10L, persistedAlias.createdAt)
            assertNull(persistedAlias.activatedAt)
            val persistedTombstone = transaction.tombstones().single()
            assertEquals("items", persistedTombstone.namespace)
            assertEquals("item-1", persistedTombstone.canonicalId)
            assertEquals(CLIENT_ID, persistedTombstone.createdByClientId)
            assertEquals(1L, persistedTombstone.createdBySequence)
            assertEquals(MutationTombstoneState.PENDING, persistedTombstone.state)
            assertEquals(10L, persistedTombstone.createdAt)
            assertNull(persistedTombstone.activatedAt)
            assertNull(persistedTombstone.supersededByClientId)
            assertNull(persistedTombstone.supersededBySequence)
            assertNull(persistedTombstone.supersededAt)
        }
    }

    @Test
    public fun preparation_attemptEffectsAndReadyCommitAtomically(): TestResult = runTest {
        val storage = createStorage()
        appendIntent(storage, sequence = 1L)
        val failure = Rollback()
        val caught =
            assertFailsWith<Rollback> {
                storage.transaction { transaction ->
                    transaction.insertAttempt(attempt(sequence = 1L, generation = 1))
                    transaction.insertEffect(effect(sequence = 1L, index = 0))
                    transaction.advanceExecution(
                        execution(
                            sequence = 1L,
                            phase = MutationExecutionPhase.READY,
                            generation = 1,
                        ),
                    )
                    throw failure
                }
            }
        assertSame(failure, caught)
        storage.transaction { transaction ->
            assertTrue(transaction.attempts(CLIENT_ID).isEmpty())
            assertTrue(transaction.effects(CLIENT_ID).isEmpty())
            assertEquals(MutationExecutionPhase.UNPREPARED, transaction.executions(CLIENT_ID).single().phase)
        }

        storage.transaction { transaction ->
            transaction.insertAttempt(attempt(sequence = 1L, generation = 1))
            transaction.insertEffect(effect(sequence = 1L, index = 0))
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.READY,
                    generation = 1,
                ),
            )
        }
        storage.transaction { transaction ->
            assertEquals(1, transaction.attempts(CLIENT_ID).size)
            assertEquals(1, transaction.effects(CLIENT_ID).size)
            assertEquals(MutationExecutionPhase.READY, transaction.executions(CLIENT_ID).single().phase)
        }
    }

    @Test
    public fun serverWins_skipRetireAndPrefixCommitAtomically(): TestResult = runTest {
        val storage = createStorage()
        appendIntent(storage, sequence = 1L)
        prepareConflict(storage, sequence = 1L)
        val failure = Rollback()
        assertFailsWith<Rollback> {
            storage.transaction { transaction ->
                transaction.advanceEffect(
                    effect(
                        sequence = 1L,
                        index = 0,
                        disposition = MutationEffectDisposition.SKIPPED,
                        completedAt = 50L,
                    ),
                )
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.RETIRED,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 40L,
                        retiredAt = 50L,
                    ),
                )
                transaction.advanceClient(
                    client(lastAllocated = 1L, retiredThrough = 1L),
                )
                throw failure
            }
        }
        storage.transaction { transaction ->
            assertEquals(MutationEffectDisposition.PENDING, transaction.effects(CLIENT_ID).single().disposition)
            assertEquals(MutationExecutionPhase.REFRESH_REQUIRED, transaction.executions(CLIENT_ID).single().phase)
            assertEquals(0L, requireNotNull(transaction.client(CLIENT_ID)).retiredThroughSequence)
        }

        storage.transaction { transaction ->
            transaction.advanceEffect(
                effect(
                    sequence = 1L,
                    index = 0,
                    disposition = MutationEffectDisposition.SKIPPED,
                    completedAt = 50L,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.RETIRED,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 40L,
                    retiredAt = 50L,
                ),
            )
            transaction.advanceClient(client(lastAllocated = 1L, retiredThrough = 1L))
        }
        storage.transaction { transaction ->
            assertEquals(MutationEffectDisposition.SKIPPED, transaction.effects(CLIENT_ID).single().disposition)
            assertEquals(MutationExecutionPhase.RETIRED, transaction.executions(CLIENT_ID).single().phase)
            assertEquals(1L, requireNotNull(transaction.client(CLIENT_ID)).retiredThroughSequence)
        }
    }

    @Test
    public fun ackReceipt_ackRoutingGenerationAndAckedCommitAtomically(): TestResult = runTest {
        val versionedStorage = createStorage()
        appendIntent(versionedStorage, sequence = 1L)
        versionedStorage.transaction { transaction ->
            transaction.insertAttempt(
                attempt(
                    sequence = 1L,
                    generation = 1,
                    valueCodecVersion = 7,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.READY,
                    generation = 1,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.INFLIGHT,
                    generation = 1,
                ),
            )
        }
        val currentVersionAck =
            ack(
                sequence = 1L,
                generation = 1,
                authoritative = byteArrayOf(9, 7),
                valueCodecVersion = 9,
            )
        assertFailsWith<Rollback> {
            versionedStorage.transaction { transaction ->
                transaction.insertAck(currentVersionAck)
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.ACKED,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 50L,
                    ),
                )
                throw Rollback()
            }
        }
        versionedStorage.transaction { transaction ->
            assertTrue(transaction.acks(CLIENT_ID).isEmpty())
            assertEquals(MutationExecutionPhase.INFLIGHT, transaction.executions(CLIENT_ID).single().phase)
        }

        versionedStorage.transaction { transaction ->
            transaction.insertAck(currentVersionAck)
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.ACKED,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 50L,
                ),
            )
        }
        versionedStorage.transaction { transaction ->
            transaction.insertAck(currentVersionAck)
        }
        assertFailsWith<IllegalArgumentException> {
            versionedStorage.transaction { transaction ->
                transaction.insertAck(
                    ack(
                        sequence = 1L,
                        generation = 1,
                        authoritative = byteArrayOf(9, 8),
                        valueCodecVersion = 9,
                    ),
                )
            }
        }
        reopenStorage(versionedStorage).transaction { transaction ->
            val persisted = transaction.acks(CLIENT_ID).single()
            assertEquals(9, persisted.valueCodecVersion)
            assertContentEquals(byteArrayOf(9, 7), persisted.authoritativeBlob)
            assertEquals(MutationExecutionPhase.ACKED, transaction.executions(CLIENT_ID).single().phase)
        }

        val aliasStorage = createStorage()
        appendIntent(aliasStorage, sequence = 1L)
        prepareForPush(aliasStorage, sequence = 1L)
        assertFailsWith<Rollback> {
            aliasStorage.transaction { transaction ->
                transaction.insertAck(ack(sequence = 1L, generation = 1))
                transaction.insertAlias(alias(sequence = 1L))
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.ACKED,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 50L,
                    ),
                )
                throw Rollback()
            }
        }
        aliasStorage.transaction { transaction ->
            assertTrue(transaction.acks(CLIENT_ID).isEmpty())
            assertTrue(transaction.aliases().isEmpty())
            assertEquals(MutationExecutionPhase.INFLIGHT, transaction.executions(CLIENT_ID).single().phase)
        }

        aliasStorage.transaction { transaction ->
            transaction.insertAck(ack(sequence = 1L, generation = 1))
            transaction.insertAlias(alias(sequence = 1L))
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.ACKED,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 50L,
                ),
            )
        }
        aliasStorage.transaction { transaction ->
            assertEquals(1, transaction.acks(CLIENT_ID).size)
            assertEquals(MutationAliasState.PENDING, transaction.aliases().single().state)
            assertEquals(MutationExecutionPhase.ACKED, transaction.executions(CLIENT_ID).single().phase)
        }

        val tombstoneStorage = createStorage()
        appendIntent(tombstoneStorage, sequence = 1L)
        prepareForPush(tombstoneStorage, sequence = 1L)
        assertFailsWith<Rollback> {
            tombstoneStorage.transaction { transaction ->
                transaction.insertAck(absentAck(sequence = 1L, generation = 1))
                transaction.insertTombstone(tombstone(sequence = 1L))
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.ACKED,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 50L,
                    ),
                )
                throw Rollback()
            }
        }
        tombstoneStorage.transaction { transaction ->
            assertTrue(transaction.acks(CLIENT_ID).isEmpty())
            assertTrue(transaction.tombstones().isEmpty())
            assertEquals(MutationExecutionPhase.INFLIGHT, transaction.executions(CLIENT_ID).single().phase)
        }
        tombstoneStorage.transaction { transaction ->
            transaction.insertAck(absentAck(sequence = 1L, generation = 1))
            transaction.insertTombstone(tombstone(sequence = 1L))
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.ACKED,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 50L,
                ),
            )
        }
        tombstoneStorage.transaction { transaction ->
            assertEquals(MutationPresenceState.ABSENT, transaction.acks(CLIENT_ID).single().authoritativePresence)
            assertEquals(MutationTombstoneState.PENDING, transaction.tombstones().single().state)
            assertEquals(MutationExecutionPhase.ACKED, transaction.executions(CLIENT_ID).single().phase)
        }
    }

    @Test
    public fun adoptionAdvance_commitsAtomically(): TestResult = runTest {
        val storage = createStorage()
        appendIntent(storage, sequence = 1L)
        acknowledge(storage, sequence = 1L)
        assertFailsWith<Rollback> {
            storage.transaction { transaction ->
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.EFFECTS_PENDING,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 50L,
                    ),
                )
                throw Rollback()
            }
        }
        assertEquals(
            MutationExecutionPhase.ACKED,
            storage.transaction { it.executions(CLIENT_ID).single().phase },
        )

        storage.transaction { transaction ->
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.EFFECTS_PENDING,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 50L,
                ),
            )
        }
        assertEquals(
            MutationExecutionPhase.EFFECTS_PENDING,
            storage.transaction { it.executions(CLIENT_ID).single().phase },
        )
    }

    @Test
    public fun retirementFinalization_highWaterAndRoutingCommitAtomically(): TestResult = runTest {
        val aliasStorage = createStorage()
        appendIntent(aliasStorage, sequence = 1L)
        prepareForPush(aliasStorage, sequence = 1L, includeEffect = true)
        aliasStorage.transaction { transaction ->
            transaction.insertAck(ack(sequence = 1L, generation = 1))
            transaction.insertAlias(alias(sequence = 1L))
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.ACKED,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 50L,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.EFFECTS_PENDING,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 50L,
                ),
            )
            transaction.advanceEffect(
                effect(
                    sequence = 1L,
                    index = 0,
                    disposition = MutationEffectDisposition.APPLIED,
                    completedAt = 60L,
                ),
            )
        }

        assertFailsWith<Rollback> {
            aliasStorage.transaction { transaction ->
                finalizeAliasRetirement(transaction)
                throw Rollback()
            }
        }
        aliasStorage.transaction { transaction ->
            assertEquals(MutationExecutionPhase.EFFECTS_PENDING, transaction.executions(CLIENT_ID).single().phase)
            assertEquals(0L, requireNotNull(transaction.client(CLIENT_ID)).retiredThroughSequence)
            assertEquals(MutationAliasState.PENDING, transaction.aliases().single().state)
        }

        aliasStorage.transaction(::finalizeAliasRetirement)
        aliasStorage.transaction { transaction ->
            assertEquals(MutationExecutionPhase.RETIRED, transaction.executions(CLIENT_ID).single().phase)
            assertEquals(1L, requireNotNull(transaction.client(CLIENT_ID)).retiredThroughSequence)
            assertEquals(MutationAliasState.ACTIVE, transaction.aliases().single().state)
        }

        val tombstoneStorage = createStorage()
        appendIntent(tombstoneStorage, sequence = 1L)
        prepareForPush(tombstoneStorage, sequence = 1L, includeEffect = true)
        tombstoneStorage.transaction { transaction ->
            transaction.insertAck(absentAck(sequence = 1L, generation = 1))
            transaction.insertTombstone(tombstone(createdByClientId = OLDER_CLIENT_ID))
            transaction.advanceTombstone(
                tombstone(
                    createdByClientId = OLDER_CLIENT_ID,
                    state = MutationTombstoneState.ACTIVE,
                    activatedAt = 5L,
                ),
            )
            transaction.insertTombstone(tombstone(sequence = 1L))
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.ACKED,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 50L,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.EFFECTS_PENDING,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 50L,
                ),
            )
            transaction.advanceEffect(
                effect(
                    sequence = 1L,
                    index = 0,
                    disposition = MutationEffectDisposition.APPLIED,
                    completedAt = 60L,
                ),
            )
        }
        assertFailsWith<Rollback> {
            tombstoneStorage.transaction { transaction ->
                finalizeTombstoneRetirement(transaction)
                throw Rollback()
            }
        }
        tombstoneStorage.transaction { transaction ->
            assertEquals(MutationExecutionPhase.EFFECTS_PENDING, transaction.executions(CLIENT_ID).single().phase)
            assertEquals(0L, requireNotNull(transaction.client(CLIENT_ID)).retiredThroughSequence)
            val byCreator = transaction.tombstones().associateBy { it.createdByClientId }
            assertEquals(MutationTombstoneState.ACTIVE, byCreator.getValue(OLDER_CLIENT_ID).state)
            assertEquals(MutationTombstoneState.PENDING, byCreator.getValue(CLIENT_ID).state)
        }
        tombstoneStorage.transaction(::finalizeTombstoneRetirement)
        tombstoneStorage.transaction { transaction ->
            assertEquals(MutationExecutionPhase.RETIRED, transaction.executions(CLIENT_ID).single().phase)
            assertEquals(1L, requireNotNull(transaction.client(CLIENT_ID)).retiredThroughSequence)
            val byCreator = transaction.tombstones().associateBy { it.createdByClientId }
            assertEquals(MutationTombstoneState.SUPERSEDED, byCreator.getValue(OLDER_CLIENT_ID).state)
            assertEquals(MutationTombstoneState.ACTIVE, byCreator.getValue(CLIENT_ID).state)
        }

        CausalFinalizationFault.entries.forEach { fault ->
            val candidate = causalLowerSuccessorStorage(fault)
            val before = causalFinalizationSnapshot(candidate)
            assertFailsWith<IllegalArgumentException>(fault.name) {
                candidate.transaction { transaction ->
                    finalizeCausalLowerSuccessor(transaction, fault)
                }
            }
            assertEquals(before, causalFinalizationSnapshot(candidate), fault.name)
        }

        val causalStorage = causalLowerSuccessorStorage(fault = null)
        causalStorage.transaction { transaction ->
            finalizeCausalLowerSuccessor(transaction, fault = null)
        }
        val causalFinal = causalFinalizationSnapshot(causalStorage)
        assertEquals(3L, causalFinal.retiredThroughSequence)
        assertEquals(
            MutationExecutionPhase.RETIRED,
            causalFinal.executions.single { it.sequence == 1L }.phase,
        )
        assertEquals(
            MutationAliasState.ACTIVE,
            causalFinal.aliases.single { it.sourceCanonicalId == CAUSAL_SOURCE_ID }.state,
        )
        val supersededCausalTombstones =
            causalFinal.tombstones
                .filter { it.canonicalId == CAUSAL_MIDDLE_ID || it.canonicalId == CAUSAL_TARGET_ID }
        assertEquals(2, supersededCausalTombstones.size)
        supersededCausalTombstones.forEach { tombstone ->
            assertEquals(MutationTombstoneState.SUPERSEDED, tombstone.state)
            assertEquals(CLIENT_ID, tombstone.supersededByClientId)
            assertEquals(1L, tombstone.supersededBySequence)
            assertEquals(
                if (tombstone.canonicalId == CAUSAL_MIDDLE_ID) 60L else 61L,
                tombstone.activatedAt,
            )
        }

        appendIntent(
            storage = causalStorage,
            sequence = 4L,
            canonicalId = "post-causal-release",
        )
        causalStorage.transaction { transaction ->
            transaction.insertAttempt(
                attempt(
                    sequence = 4L,
                    generation = 1,
                    effectiveCanonicalId = "post-causal-release",
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 4L,
                    phase = MutationExecutionPhase.READY,
                    generation = 1,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = 4L,
                    phase = MutationExecutionPhase.INFLIGHT,
                    generation = 1,
                ),
            )
        }
        assertEquals(
            MutationExecutionPhase.INFLIGHT,
            causalStorage.transaction { transaction ->
                transaction.executions(CLIENT_ID).single { it.clientSequence == 4L }.phase
            },
        )
    }

    @Test
    public fun parking_failurePhaseAndActiveFailureCommitAtomically(): TestResult = runTest {
        val storage = createStorage()
        appendIntent(storage, sequence = 1L)
        val failure = Rollback()
        assertFailsWith<Rollback> {
            storage.transaction { transaction ->
                val record = appendParkedFailure(transaction)
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.PARKED,
                        activeFailureId = record.failureId,
                    ),
                )
                throw failure
            }
        }
        storage.transaction { transaction ->
            assertTrue(transaction.failures(CLIENT_ID).isEmpty())
            assertEquals(MutationExecutionPhase.UNPREPARED, transaction.executions(CLIENT_ID).single().phase)
        }

        storage.transaction { transaction ->
            val record = appendParkedFailure(transaction)
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.PARKED,
                    activeFailureId = record.failureId,
                ),
            )
        }
        storage.transaction { transaction ->
            val record = transaction.failures(CLIENT_ID).single()
            val execution = transaction.executions(CLIENT_ID).single()
            assertEquals(MutationExecutionPhase.PARKED, execution.phase)
            assertEquals(record.failureId, execution.activeFailureId)
        }
    }

    private enum class CausalFinalizationFault {
        PREDECESSOR_NOT_ACTIVE,
        SUCCESSOR_NOT_EFFECTS_PENDING,
        ACK_NOT_PRESENT,
        ACK_DOES_NOT_OWN_ALIAS,
        EFFECT_PENDING,
        ALIAS_NOT_ACTIVATED,
        TERMINAL_MISSES_PREDECESSOR,
        SUCCESSOR_NOT_RETIRED,
        PREFIX_NOT_ADVANCED,
        COLLAPSED_TOMBSTONE_NOT_SUPERSEDED,
        ACTIVATION_TIMESTAMP_CHANGED,
        SUCCESSOR_IDENTITY_MISMATCH,
        SUCCESSOR_CLIENT_MISMATCH,
    }

    private data class CausalExecutionSnapshot(
        val sequence: Long,
        val phase: MutationExecutionPhase,
        val attempt: Int,
        val lastAttemptAt: Long?,
        val retiredAt: Long?,
    )

    private data class CausalAliasSnapshot(
        val sourceCanonicalId: String,
        val targetCanonicalId: String,
        val state: MutationAliasState,
        val createdBySequence: Long,
        val activatedAt: Long?,
    )

    private data class CausalTombstoneSnapshot(
        val canonicalId: String,
        val state: MutationTombstoneState,
        val activatedAt: Long?,
        val supersededByClientId: String?,
        val supersededBySequence: Long?,
        val supersededAt: Long?,
    )

    private data class CausalEffectSnapshot(
        val index: Int,
        val disposition: MutationEffectDisposition,
        val completedAt: Long?,
    )

    private data class CausalFinalizationSnapshot(
        val retiredThroughSequence: Long,
        val executions: List<CausalExecutionSnapshot>,
        val aliases: List<CausalAliasSnapshot>,
        val tombstones: List<CausalTombstoneSnapshot>,
        val effects: List<CausalEffectSnapshot>,
    )

    private suspend fun causalLowerSuccessorStorage(
        fault: CausalFinalizationFault?,
    ): MutationJournalStorage =
        createStorage().also { storage ->
            storage.transaction { transaction ->
                transaction.insertClient(client())
                transaction.advanceClient(client(lastAllocated = 3L))
                listOf(
                    Triple(1L, CAUSAL_SOURCE_ID, "source-mutation"),
                    Triple(2L, CAUSAL_MIDDLE_ID, "middle-mutation"),
                    Triple(3L, CAUSAL_TARGET_ID, "target-mutation"),
                ).forEach { (sequence, canonicalId, mutationId) ->
                    insertIntent(
                        transaction = transaction,
                        sequence = sequence,
                        mutationId = mutationId,
                        canonicalId = canonicalId,
                    )
                    transaction.insertExecution(execution(sequence = sequence))
                }
            }

            storage.transaction { transaction ->
                transaction.insertAttempt(
                    attempt(
                        sequence = 2L,
                        generation = 1,
                        effectiveCanonicalId = CAUSAL_MIDDLE_ID,
                    ),
                )
                transaction.advanceExecution(
                    execution(
                        sequence = 2L,
                        phase = MutationExecutionPhase.READY,
                        generation = 1,
                    ),
                )
                transaction.advanceExecution(
                    execution(
                        sequence = 2L,
                        phase = MutationExecutionPhase.INFLIGHT,
                        generation = 1,
                    ),
                )
                transaction.insertAck(sameIdentityAck(sequence = 2L, generation = 1))
                transaction.advanceExecution(
                    execution(
                        sequence = 2L,
                        phase = MutationExecutionPhase.ACKED,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 32L,
                    ),
                )
                transaction.advanceExecution(
                    execution(
                        sequence = 2L,
                        phase = MutationExecutionPhase.EFFECTS_PENDING,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 32L,
                    ),
                )
                transaction.insertAlias(
                    alias(
                        sourceId = CAUSAL_MIDDLE_ID,
                        targetId = CAUSAL_TARGET_ID,
                        sequence = 2L,
                    ),
                )
                transaction.advanceAlias(
                    alias(
                        sourceId = CAUSAL_MIDDLE_ID,
                        targetId = CAUSAL_TARGET_ID,
                        sequence = 2L,
                        state = MutationAliasState.ACTIVE,
                        activatedAt = 42L,
                    ),
                )
                transaction.advanceExecution(
                    execution(
                        sequence = 2L,
                        phase = MutationExecutionPhase.RETIRED,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 32L,
                        retiredAt = 42L,
                    ),
                )
            }

            storage.transaction { transaction ->
                transaction.insertAttempt(
                    attempt(
                        sequence = 3L,
                        generation = 1,
                        effectiveCanonicalId = CAUSAL_TARGET_ID,
                    ),
                )
                transaction.advanceExecution(
                    execution(
                        sequence = 3L,
                        phase = MutationExecutionPhase.READY,
                        generation = 1,
                    ),
                )
                transaction.advanceExecution(
                    execution(
                        sequence = 3L,
                        phase = MutationExecutionPhase.INFLIGHT,
                        generation = 1,
                    ),
                )
                transaction.insertAck(absentAck(sequence = 3L, generation = 1))
                listOf(CAUSAL_MIDDLE_ID, CAUSAL_TARGET_ID).forEachIndexed { index, canonicalId ->
                    transaction.insertTombstone(
                        tombstone(
                            sequence = 3L,
                            canonicalId = canonicalId,
                            createdAt = 50L + index,
                        ),
                    )
                    if (
                        canonicalId != CAUSAL_TARGET_ID ||
                        fault != CausalFinalizationFault.PREDECESSOR_NOT_ACTIVE
                    ) {
                        transaction.advanceTombstone(
                            tombstone(
                                sequence = 3L,
                                canonicalId = canonicalId,
                                state = MutationTombstoneState.ACTIVE,
                                createdAt = 50L + index,
                                activatedAt = 60L + index,
                            ),
                        )
                    }
                }
                transaction.advanceExecution(
                    execution(
                        sequence = 3L,
                        phase = MutationExecutionPhase.ACKED,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 33L,
                    ),
                )
                transaction.advanceExecution(
                    execution(
                        sequence = 3L,
                        phase = MutationExecutionPhase.EFFECTS_PENDING,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 33L,
                    ),
                )
                transaction.advanceExecution(
                    execution(
                        sequence = 3L,
                        phase = MutationExecutionPhase.RETIRED,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 33L,
                        retiredAt = 43L,
                    ),
                )
            }

            storage.transaction { transaction ->
                val aliasTarget =
                    if (fault == CausalFinalizationFault.TERMINAL_MISSES_PREDECESSOR) {
                        CAUSAL_UNRELATED_ID
                    } else {
                        CAUSAL_MIDDLE_ID
                    }
                val acknowledgementTarget =
                    if (fault == CausalFinalizationFault.ACK_DOES_NOT_OWN_ALIAS) {
                        CAUSAL_UNRELATED_ID
                    } else {
                        aliasTarget
                    }
                transaction.insertAttempt(
                    attempt(
                        sequence = 1L,
                        generation = 1,
                        effectiveCanonicalId = CAUSAL_SOURCE_ID,
                    ),
                )
                transaction.insertEffect(effect(sequence = 1L, index = 0))
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.READY,
                        generation = 1,
                    ),
                )
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.INFLIGHT,
                        generation = 1,
                    ),
                )
                if (fault == CausalFinalizationFault.ACK_NOT_PRESENT) {
                    transaction.insertAck(absentAck(sequence = 1L, generation = 1))
                } else {
                    transaction.insertAck(
                        ack(
                            sequence = 1L,
                            generation = 1,
                            canonicalTargetId = acknowledgementTarget,
                        ),
                    )
                }
                transaction.insertAlias(
                    alias(
                        sourceId = CAUSAL_SOURCE_ID,
                        targetId = aliasTarget,
                        sequence = 1L,
                    ),
                )
                transaction.advanceExecution(
                    execution(
                        sequence = 1L,
                        phase = MutationExecutionPhase.ACKED,
                        generation = 1,
                        attemptCount = 1,
                        lastAttemptAt = 31L,
                    ),
                )
                if (fault != CausalFinalizationFault.SUCCESSOR_NOT_EFFECTS_PENDING) {
                    transaction.advanceExecution(
                        execution(
                            sequence = 1L,
                            phase = MutationExecutionPhase.EFFECTS_PENDING,
                            generation = 1,
                            attemptCount = 1,
                            lastAttemptAt = 31L,
                        ),
                    )
                }
                if (fault != CausalFinalizationFault.EFFECT_PENDING) {
                    transaction.advanceEffect(
                        effect(
                            sequence = 1L,
                            index = 0,
                            disposition = MutationEffectDisposition.APPLIED,
                            completedAt = 65L,
                        ),
                    )
                }
            }
        }

    private fun finalizeCausalLowerSuccessor(
        transaction: MutationJournalTransaction,
        fault: CausalFinalizationFault?,
    ) {
        val aliasTarget =
            transaction.aliases().single { it.sourceCanonicalId == CAUSAL_SOURCE_ID }.targetCanonicalId
        if (fault != CausalFinalizationFault.ALIAS_NOT_ACTIVATED) {
            transaction.advanceAlias(
                alias(
                    sourceId = CAUSAL_SOURCE_ID,
                    targetId = aliasTarget,
                    sequence = 1L,
                    state = MutationAliasState.ACTIVE,
                    activatedAt = 70L,
                ),
            )
        }
        if (fault != CausalFinalizationFault.SUCCESSOR_NOT_RETIRED) {
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.RETIRED,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 31L,
                    retiredAt = 70L,
                ),
            )
        }
        if (fault != CausalFinalizationFault.PREFIX_NOT_ADVANCED) {
            transaction.advanceClient(client(lastAllocated = 3L, retiredThrough = 3L))
        }
        listOf(CAUSAL_TARGET_ID, CAUSAL_MIDDLE_ID).forEachIndexed { index, canonicalId ->
            if (
                canonicalId != CAUSAL_MIDDLE_ID ||
                fault != CausalFinalizationFault.COLLAPSED_TOMBSTONE_NOT_SUPERSEDED
            ) {
                transaction.advanceTombstone(
                    tombstone(
                        sequence = 3L,
                        canonicalId = canonicalId,
                        state = MutationTombstoneState.SUPERSEDED,
                        createdAt = if (canonicalId == CAUSAL_MIDDLE_ID) 50L else 51L,
                        activatedAt =
                            when {
                                fault == CausalFinalizationFault.ACTIVATION_TIMESTAMP_CHANGED &&
                                    canonicalId == CAUSAL_TARGET_ID -> 999L
                                canonicalId == CAUSAL_MIDDLE_ID -> 60L
                                else -> 61L
                            },
                        supersededByClientId =
                            if (
                                fault == CausalFinalizationFault.SUCCESSOR_CLIENT_MISMATCH &&
                                index == 0
                            ) {
                                OLDER_CLIENT_ID
                            } else {
                                CLIENT_ID
                            },
                        supersededBySequence =
                            if (
                                fault == CausalFinalizationFault.SUCCESSOR_IDENTITY_MISMATCH &&
                                index == 0
                            ) {
                                2L
                            } else {
                                1L
                            },
                        supersededAt = 70L,
                    ),
                )
            }
        }
    }

    private suspend fun causalFinalizationSnapshot(
        storage: MutationJournalStorage,
    ): CausalFinalizationSnapshot =
        storage.transaction { transaction ->
            CausalFinalizationSnapshot(
                retiredThroughSequence =
                    requireNotNull(transaction.client(CLIENT_ID)).retiredThroughSequence,
                executions =
                    transaction.executions(CLIENT_ID).map { execution ->
                        CausalExecutionSnapshot(
                            sequence = execution.clientSequence,
                            phase = execution.phase,
                            attempt = execution.attempt,
                            lastAttemptAt = execution.lastAttemptAt,
                            retiredAt = execution.retiredAt,
                        )
                    },
                aliases =
                    transaction.aliases().map { alias ->
                        CausalAliasSnapshot(
                            sourceCanonicalId = alias.sourceCanonicalId,
                            targetCanonicalId = alias.targetCanonicalId,
                            state = alias.state,
                            createdBySequence = alias.createdBySequence,
                            activatedAt = alias.activatedAt,
                        )
                    },
                tombstones =
                    transaction.tombstones().map { tombstone ->
                        CausalTombstoneSnapshot(
                            canonicalId = tombstone.canonicalId,
                            state = tombstone.state,
                            activatedAt = tombstone.activatedAt,
                            supersededByClientId = tombstone.supersededByClientId,
                            supersededBySequence = tombstone.supersededBySequence,
                            supersededAt = tombstone.supersededAt,
                        )
                    },
                effects =
                    transaction.effects(CLIENT_ID).map { effect ->
                        CausalEffectSnapshot(
                            index = effect.effectIndex,
                            disposition = effect.disposition,
                            completedAt = effect.completedAt,
                        )
                    },
            )
        }

    private suspend fun appendIntent(
        storage: MutationJournalStorage,
        sequence: Long,
        namespace: String = "items",
        canonicalId: String = "item-$sequence",
    ): MutationIntentRecord =
        storage.transaction { transaction ->
            val current = transaction.client(CLIENT_ID)
            if (current == null) {
                transaction.insertClient(client())
                transaction.advanceClient(client(lastAllocated = sequence))
            } else {
                transaction.advanceClient(
                    client(
                        lastAllocated = sequence,
                        retiredThrough = current.retiredThroughSequence,
                        confirmedThrough = current.serverConfirmedRetiredThroughSequence,
                    ),
                )
            }
            val intent =
                insertIntent(
                    transaction = transaction,
                    sequence = sequence,
                    namespace = namespace,
                    canonicalId = canonicalId,
                )
            transaction.insertExecution(execution(sequence = sequence))
            intent
        }

    private fun insertIntent(
        transaction: MutationJournalTransaction,
        sequence: Long,
        mutationId: String = "mutation-$sequence",
        args: ByteArray = byteArrayOf(1, 2, 3),
        namespace: String = "items",
        canonicalId: String = "item-$sequence",
    ): MutationIntentRecord =
        transaction.insertIntent(
            recordVersion = 1,
            clientId = CLIENT_ID,
            clientSequence = sequence,
            mutationId = mutationId,
            namespace = namespace,
            canonicalId = canonicalId,
            mutatorId = "mutator",
            mutatorVersion = 1,
            argsBlob = args,
            idempotencyRoot = "root-$sequence",
            createdAt = 10L + sequence,
        )

    private suspend fun prepareForPush(
        storage: MutationJournalStorage,
        sequence: Long,
        includeEffect: Boolean = false,
    ) {
        storage.transaction { transaction ->
            transaction.insertAttempt(attempt(sequence = sequence, generation = 1))
            if (includeEffect) {
                transaction.insertEffect(effect(sequence = sequence, index = 0))
            }
            transaction.advanceExecution(
                execution(
                    sequence = sequence,
                    phase = MutationExecutionPhase.READY,
                    generation = 1,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = sequence,
                    phase = MutationExecutionPhase.INFLIGHT,
                    generation = 1,
                ),
            )
        }
    }

    private suspend fun c8OwnerPredicateStorage(ownerState: C8OwnerState): MutationJournalStorage {
        val storage = createStorage()
        appendIntent(storage, sequence = 1L, canonicalId = "predicate-owner")
        appendIntent(storage, sequence = 2L, canonicalId = "predicate-contender")
        storage.transaction { transaction ->
            listOf(1L, 2L).forEach { sequence ->
                transaction.insertAttempt(
                    attempt(
                        sequence = sequence,
                        generation = 1,
                        effectiveCanonicalId =
                            if (sequence == 1L) "predicate-owner" else "predicate-contender",
                    ),
                )
                transaction.advanceExecution(
                    execution(
                        sequence = sequence,
                        phase = MutationExecutionPhase.READY,
                        generation = 1,
                    ),
                )
            }
            transaction.advanceExecution(
                execution(
                    sequence = 1L,
                    phase = MutationExecutionPhase.INFLIGHT,
                    generation = 1,
                ),
            )

            when (ownerState) {
                C8OwnerState.INFLIGHT -> Unit
                C8OwnerState.RETRY_READY ->
                    transaction.advanceExecution(
                        execution(
                            sequence = 1L,
                            phase = MutationExecutionPhase.READY,
                            generation = 1,
                            attemptCount = 1,
                            lastAttemptAt = 30L,
                        ),
                    )
                C8OwnerState.REFRESH_REQUIRED,
                C8OwnerState.NEXT_GENERATION_READY,
                -> {
                    transaction.recordConflictReceipt(
                        attempt(
                            sequence = 1L,
                            generation = 1,
                            effectiveCanonicalId = "predicate-owner",
                            conflictMetaPresent = false,
                            conflictReceivedAt = 40L,
                        ),
                    )
                    transaction.advanceExecution(
                        execution(
                            sequence = 1L,
                            phase = MutationExecutionPhase.REFRESH_REQUIRED,
                            generation = 1,
                            attemptCount = 1,
                            lastAttemptAt = 40L,
                        ),
                    )
                    if (ownerState == C8OwnerState.NEXT_GENERATION_READY) {
                        transaction.insertAttempt(
                            attempt(
                                sequence = 1L,
                                generation = 2,
                                effectiveCanonicalId = "predicate-owner",
                            ),
                        )
                        transaction.advanceExecution(
                            execution(
                                sequence = 1L,
                                phase = MutationExecutionPhase.READY,
                                generation = 2,
                            ),
                        )
                    }
                }
                C8OwnerState.ACKED,
                C8OwnerState.EFFECTS_PENDING,
                -> {
                    transaction.insertAck(sameIdentityAck(sequence = 1L, generation = 1))
                    transaction.advanceExecution(
                        execution(
                            sequence = 1L,
                            phase = MutationExecutionPhase.ACKED,
                            generation = 1,
                            attemptCount = 1,
                            lastAttemptAt = 50L,
                        ),
                    )
                    if (ownerState == C8OwnerState.EFFECTS_PENDING) {
                        transaction.advanceExecution(
                            execution(
                                sequence = 1L,
                                phase = MutationExecutionPhase.EFFECTS_PENDING,
                                generation = 1,
                                attemptCount = 1,
                                lastAttemptAt = 50L,
                            ),
                        )
                    }
                }
            }
        }
        return storage
    }

    private suspend fun prepareConflict(
        storage: MutationJournalStorage,
        sequence: Long,
    ) {
        storage.transaction { transaction ->
            transaction.insertAttempt(attempt(sequence = sequence, generation = 1))
            transaction.insertEffect(effect(sequence = sequence, index = 0))
            transaction.advanceExecution(
                execution(
                    sequence = sequence,
                    phase = MutationExecutionPhase.READY,
                    generation = 1,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = sequence,
                    phase = MutationExecutionPhase.INFLIGHT,
                    generation = 1,
                ),
            )
            transaction.recordConflictReceipt(
                attempt(
                    sequence = sequence,
                    generation = 1,
                    conflictMetaPresent = false,
                    conflictReceivedAt = 40L,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = sequence,
                    phase = MutationExecutionPhase.REFRESH_REQUIRED,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 40L,
                ),
            )
        }
    }

    private suspend fun acknowledge(
        storage: MutationJournalStorage,
        sequence: Long,
    ) {
        prepareForPush(storage, sequence)
        storage.transaction { transaction ->
            transaction.insertAck(ack(sequence = sequence, generation = 1))
            transaction.advanceExecution(
                execution(
                    sequence = sequence,
                    phase = MutationExecutionPhase.ACKED,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 50L,
                ),
            )
        }
    }

    private suspend fun retireForPrune(
        storage: MutationJournalStorage,
        sequence: Long,
        retiredThrough: Long,
    ) {
        prepareForPush(storage, sequence, includeEffect = true)
        storage.transaction { transaction ->
            transaction.insertAck(sameIdentityAck(sequence = sequence, generation = 1))
            transaction.appendFailure(
                clientId = CLIENT_ID,
                clientSequence = sequence,
                generation = 1,
                kind = MutationFailureKind.EFFECT,
                detail = "retryable-effect",
                message = "retained retryable evidence",
                occurredAt = 45L + sequence,
            )
            transaction.advanceExecution(
                execution(
                    sequence = sequence,
                    phase = MutationExecutionPhase.ACKED,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 40L,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = sequence,
                    phase = MutationExecutionPhase.EFFECTS_PENDING,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 40L,
                ),
            )
            transaction.advanceEffect(
                effect(
                    sequence = sequence,
                    index = 0,
                    disposition = MutationEffectDisposition.APPLIED,
                    completedAt = 50L + sequence,
                ),
            )
            transaction.advanceExecution(
                execution(
                    sequence = sequence,
                    phase = MutationExecutionPhase.RETIRED,
                    generation = 1,
                    attemptCount = 1,
                    lastAttemptAt = 40L,
                    retiredAt = 50L + sequence,
                ),
            )
            val current = requireNotNull(transaction.client(CLIENT_ID))
            transaction.advanceClient(
                client(
                    lastAllocated = current.lastAllocatedSequence,
                    retiredThrough = retiredThrough,
                    confirmedThrough = current.serverConfirmedRetiredThroughSequence,
                ),
            )
        }
    }

    private fun finalizeAliasRetirement(transaction: MutationJournalTransaction) {
        transaction.advanceAlias(
            alias(
                sequence = 1L,
                state = MutationAliasState.ACTIVE,
                activatedAt = 70L,
            ),
        )
        transaction.advanceExecution(
            execution(
                sequence = 1L,
                phase = MutationExecutionPhase.RETIRED,
                generation = 1,
                attemptCount = 1,
                lastAttemptAt = 50L,
                retiredAt = 70L,
            ),
        )
        transaction.advanceClient(client(lastAllocated = 1L, retiredThrough = 1L))
    }

    private fun finalizeTombstoneRetirement(transaction: MutationJournalTransaction) {
        transaction.advanceTombstone(
            tombstone(
                createdByClientId = OLDER_CLIENT_ID,
                state = MutationTombstoneState.SUPERSEDED,
                activatedAt = 5L,
                supersededByClientId = CLIENT_ID,
                supersededBySequence = 1L,
                supersededAt = 70L,
            ),
        )
        transaction.advanceTombstone(
            tombstone(
                sequence = 1L,
                state = MutationTombstoneState.ACTIVE,
                activatedAt = 70L,
            ),
        )
        transaction.advanceExecution(
            execution(
                sequence = 1L,
                phase = MutationExecutionPhase.RETIRED,
                generation = 1,
                attemptCount = 1,
                lastAttemptAt = 50L,
                retiredAt = 70L,
            ),
        )
        transaction.advanceClient(client(lastAllocated = 1L, retiredThrough = 1L))
    }

    private fun appendParkedFailure(transaction: MutationJournalTransaction): MutationFailureRecord =
        transaction.appendFailure(
            clientId = CLIENT_ID,
            clientSequence = 1L,
            generation = 0,
            kind = MutationFailureKind.CODEC,
            detail = "unknown-mutator",
            message = "mutator codec unavailable",
            occurredAt = 80L,
        )

    private fun client(
        lastAllocated: Long = 0L,
        retiredThrough: Long = 0L,
        confirmedThrough: Long = 0L,
    ): MutationClientRecord =
        MutationClientRecord(
            recordVersion = 1,
            clientId = CLIENT_ID,
            lastAllocatedSequence = lastAllocated,
            retiredThroughSequence = retiredThrough,
            serverConfirmedRetiredThroughSequence = confirmedThrough,
            createdAt = 1L,
        )

    private fun execution(
        sequence: Long,
        phase: MutationExecutionPhase = MutationExecutionPhase.UNPREPARED,
        generation: Int = 0,
        attemptCount: Int = 0,
        lastAttemptAt: Long? = null,
        activeFailureId: Long? = null,
        retiredAt: Long? = null,
    ): MutationExecutionRecord =
        MutationExecutionRecord(
            clientId = CLIENT_ID,
            clientSequence = sequence,
            phase = phase,
            currentGeneration = generation,
            attempt = attemptCount,
            lastAttemptAt = lastAttemptAt,
            activeFailureId = activeFailureId,
            retiredAt = retiredAt,
        )

    private fun attempt(
        sequence: Long,
        generation: Int,
        effectiveNamespace: String = "items",
        effectiveCanonicalId: String = "item-$sequence",
        valueCodecVersion: Int = 1,
        base: ByteArray = byteArrayOf(4),
        mine: ByteArray = byteArrayOf(6),
        preconditionMetaPresent: Boolean = false,
        preconditionWrittenAt: Long? = null,
        preconditionEtag: String? = null,
        advertisedRetiredThroughSequence: Long = 0L,
        generationIdempotencyKey: String = "generation-$sequence-$generation",
        conflictMetaPresent: Boolean? = null,
        conflictWrittenAt: Long? = null,
        conflictEtag: String? = null,
        conflictReceivedAt: Long? = null,
    ): MutationAttemptRecord =
        MutationAttemptRecord(
            clientId = CLIENT_ID,
            clientSequence = sequence,
            generation = generation,
            effectiveNamespace = effectiveNamespace,
            effectiveCanonicalId = effectiveCanonicalId,
            valueCodecVersion = valueCodecVersion,
            basePresence = MutationPresenceState.PRESENT,
            baseBlob = base,
            minePresence = MutationPresenceState.PRESENT,
            mineBlob = mine,
            preconditionMetaPresent = preconditionMetaPresent,
            preconditionWrittenAt = preconditionWrittenAt,
            preconditionEtag = preconditionEtag,
            advertisedRetiredThroughSequence = advertisedRetiredThroughSequence,
            generationIdempotencyKey = generationIdempotencyKey,
            preparedAt = 20L + generation,
            conflictMetaPresent = conflictMetaPresent,
            conflictWrittenAt = conflictWrittenAt,
            conflictEtag = conflictEtag,
            conflictReceivedAt = conflictReceivedAt,
        )

    private fun ack(
        sequence: Long,
        generation: Int,
        authoritative: ByteArray = byteArrayOf(7),
        valueCodecVersion: Int = 1,
        receivedAt: Long = 50L,
        canonicalTargetNamespace: String = "items",
        canonicalTargetId: String = "canonical-$sequence",
    ): MutationAckRecord =
        MutationAckRecord(
            clientId = CLIENT_ID,
            clientSequence = sequence,
            generation = generation,
            authoritativePresence = MutationPresenceState.PRESENT,
            authoritativeBlob = authoritative,
            valueCodecVersion = valueCodecVersion,
            etag = "etag",
            canonicalTargetNamespace = canonicalTargetNamespace,
            canonicalTargetId = canonicalTargetId,
            receivedAt = receivedAt,
        )

    private fun absentAck(
        sequence: Long,
        generation: Int,
    ): MutationAckRecord =
        MutationAckRecord(
            clientId = CLIENT_ID,
            clientSequence = sequence,
            generation = generation,
            authoritativePresence = MutationPresenceState.ABSENT,
            authoritativeBlob = null,
            valueCodecVersion = 1,
            etag = "delete-etag",
            canonicalTargetNamespace = null,
            canonicalTargetId = null,
            receivedAt = 50L,
        )

    private fun sameIdentityAck(
        sequence: Long,
        generation: Int,
    ): MutationAckRecord =
        MutationAckRecord(
            clientId = CLIENT_ID,
            clientSequence = sequence,
            generation = generation,
            authoritativePresence = MutationPresenceState.PRESENT,
            authoritativeBlob = byteArrayOf(7),
            valueCodecVersion = 1,
            etag = "etag",
            canonicalTargetNamespace = null,
            canonicalTargetId = null,
            receivedAt = 40L,
        )

    private fun effect(
        sequence: Long,
        index: Int,
        kind: MutationEffectKind = MutationEffectKind.KEY,
        disposition: MutationEffectDisposition = MutationEffectDisposition.PENDING,
        completedAt: Long? = null,
    ): MutationEffectRecord =
        MutationEffectRecord(
            clientId = CLIENT_ID,
            clientSequence = sequence,
            effectIndex = index,
            kind = kind,
            namespace = "effects",
            canonicalId = if (kind == MutationEffectKind.KEY) "target-$index" else null,
            createdAt = 20L,
            disposition = disposition,
            completedAt = completedAt,
        )

    private fun alias(
        sourceId: String = "item-1",
        targetId: String = "canonical-1",
        sequence: Long = 1L,
        state: MutationAliasState = MutationAliasState.PENDING,
        activatedAt: Long? = null,
    ): MutationKeyAliasRecord =
        MutationKeyAliasRecord(
            sourceNamespace = "items",
            sourceCanonicalId = sourceId,
            targetNamespace = "items",
            targetCanonicalId = targetId,
            state = state,
            createdByClientId = CLIENT_ID,
            createdBySequence = sequence,
            createdAt = 10L,
            activatedAt = activatedAt,
        )

    private fun tombstone(
        sequence: Long = 1L,
        canonicalId: String = "item-1",
        createdByClientId: String = CLIENT_ID,
        state: MutationTombstoneState = MutationTombstoneState.PENDING,
        createdAt: Long = 10L,
        activatedAt: Long? = null,
        supersededBySequence: Long? = null,
        supersededByClientId: String? = supersededBySequence?.let { CLIENT_ID },
        supersededAt: Long? = null,
    ): MutationKeyTombstoneRecord =
        MutationKeyTombstoneRecord(
            namespace = "items",
            canonicalId = canonicalId,
            createdByClientId = createdByClientId,
            createdBySequence = sequence,
            state = state,
            createdAt = createdAt,
            activatedAt = activatedAt,
            supersededByClientId = supersededByClientId,
            supersededBySequence = supersededBySequence,
            supersededAt = supersededAt,
        )
}

private const val CLIENT_ID: String = "client"
private const val OLDER_CLIENT_ID: String = "older-client"
private const val CAUSAL_SOURCE_ID: String = "causal-source"
private const val CAUSAL_MIDDLE_ID: String = "causal-middle"
private const val CAUSAL_TARGET_ID: String = "causal-target"
private const val CAUSAL_UNRELATED_ID: String = "causal-unrelated"

private enum class C8OwnerState(
    val phase: MutationExecutionPhase,
) {
    INFLIGHT(MutationExecutionPhase.INFLIGHT),
    REFRESH_REQUIRED(MutationExecutionPhase.REFRESH_REQUIRED),
    ACKED(MutationExecutionPhase.ACKED),
    EFFECTS_PENDING(MutationExecutionPhase.EFFECTS_PENDING),
    RETRY_READY(MutationExecutionPhase.READY),
    NEXT_GENERATION_READY(MutationExecutionPhase.READY),
}

private class Rollback : RuntimeException()

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
