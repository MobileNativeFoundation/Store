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
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalTransaction
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyAliasRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyTombstoneRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationTombstoneState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Inherited deterministic crash scenarios for every mutation-journal storage implementation.
 *
 * These scenarios classify semantic transaction shapes, never ordinal transaction counts or
 * scheduler timing. [reopenStorage] is always called with the undecorated durable adapter so a
 * persistent implementation can create a fresh adapter over the same database.
 */
@ExperimentalStoreApi
public abstract class JournalStorageKillPointScenarios {
    /** Creates a fresh storage instance for one inherited scenario. */
    public abstract fun createStorage(): MutationJournalStorage

    /** Reopens [previous] without clearing its durable records. */
    public abstract fun reopenStorage(previous: MutationJournalStorage): MutationJournalStorage

    @Test
    public fun beforeRetirementFinalizationCommit_rollsBackAndClears(): TestResult =
        runKillPointTest {
            val storage = createStorage()
            seedEffectsPending(storage)
            val killed = KillPointJournalStorage(storage)
            killed.arm(JournalStorageKillPoint.BEFORE_RETIREMENT_FINALIZATION_COMMIT)

            val failure =
                assertFailsWith<JournalStorageCrashException> {
                    finalizeRetirement(killed)
                }
            assertEquals(JournalStorageKillPoint.BEFORE_RETIREMENT_FINALIZATION_COMMIT, failure.killPoint)

            val reopened = reopenStorage(storage)
            reopened.transaction { transaction ->
                assertEquals(
                    MutationExecutionPhase.EFFECTS_PENDING,
                    transaction.executions(KILL_CLIENT_ID).single().phase,
                )
                val effect = transaction.effects(KILL_CLIENT_ID).single()
                assertEquals(MutationEffectDisposition.PENDING, effect.disposition)
                assertNull(effect.completedAt)
                assertEquals(0L, requireNotNull(transaction.client(KILL_CLIENT_ID)).retiredThroughSequence)
                assertEquals(MutationAliasState.PENDING, transaction.aliases().single().state)
            }

            // The kill point cleared before throwing; the same decorator can complete once.
            finalizeRetirement(killed)
            assertEquals(
                MutationExecutionPhase.RETIRED,
                storage.transaction { it.executions(KILL_CLIENT_ID).single().phase },
            )
        }

    @Test
    public fun afterRetirementFinalizationCommit_commitsAndClears(): TestResult =
        runKillPointTest {
            val storage = createStorage()
            seedEffectsPending(storage)
            val killed = KillPointJournalStorage(storage)
            killed.arm(JournalStorageKillPoint.AFTER_RETIREMENT_FINALIZATION_COMMIT)

            val failure =
                assertFailsWith<JournalStorageCrashException> {
                    finalizeRetirement(killed)
                }
            assertEquals(JournalStorageKillPoint.AFTER_RETIREMENT_FINALIZATION_COMMIT, failure.killPoint)

            val reopened = reopenStorage(storage)
            reopened.transaction { transaction ->
                assertEquals(
                    MutationExecutionPhase.RETIRED,
                    transaction.executions(KILL_CLIENT_ID).single().phase,
                )
                assertEquals(
                    MutationEffectDisposition.APPLIED,
                    transaction.effects(KILL_CLIENT_ID).single().disposition,
                )
                assertEquals(1L, requireNotNull(transaction.client(KILL_CLIENT_ID)).retiredThroughSequence)
                assertEquals(MutationAliasState.ACTIVE, transaction.aliases().single().state)
            }
            killed.arm(JournalStorageKillPoint.AFTER_RETIREMENT_FINALIZATION_COMMIT)
        }

    /**
     * Ordinary prune removes rows only at or below the persisted server-confirmed prefix;
     * alias redirects and active or pending tombstone generations always survive.
     */
    @Test
    public fun beforePrune_abortsBeforeDeleteAndClears(): TestResult =
        pruneKillPointScenario(JournalStorageKillPoint.BEFORE_PRUNE)

    /** The same prune bound, exercised at the rollback side of the prune commit boundary. */
    @Test
    public fun beforePruneCommit_rollsBackAndClears(): TestResult =
        pruneKillPointScenario(JournalStorageKillPoint.BEFORE_PRUNE_COMMIT)

    /** The same prune bound, exercised after the prune transaction has durably committed. */
    @Test
    public fun afterPruneCommit_commitsAndClears(): TestResult =
        pruneKillPointScenario(JournalStorageKillPoint.AFTER_PRUNE_COMMIT)

    private fun pruneKillPointScenario(point: JournalStorageKillPoint): TestResult =
        runKillPointTest {
            val rawStorage = createStorage()
            seedPrunableRows(rawStorage)
            val protected = rawStorage.transaction(::protectedSnapshot)
            val killed = KillPointJournalStorage(rawStorage)
            killed.arm(point)

            val failure =
                assertFailsWith<JournalStorageCrashException> {
                    killed.transaction { transaction ->
                        transaction.prune(KILL_CLIENT_ID, serverConfirmedRetiredThroughSequence = 2L)
                    }
                }
            assertEquals(point, failure.killPoint)

            val reopened = reopenStorage(rawStorage)
            assertEquals(protected, reopened.transaction(::protectedSnapshot), "after $point")
            val retainedAfterCrash = reopened.transaction { it.intents(KILL_CLIENT_ID).map { row -> row.clientSequence } }
            if (point == JournalStorageKillPoint.AFTER_PRUNE_COMMIT) {
                assertEquals(listOf(3L, 4L), retainedAfterCrash)
            } else {
                assertEquals(listOf(1L, 2L, 3L, 4L), retainedAfterCrash)
            }

            // The point cleared before throwing; retrying the same semantic operation is safe.
            killed.transaction { transaction ->
                transaction.prune(KILL_CLIENT_ID, serverConfirmedRetiredThroughSequence = 2L)
            }
            assertEquals(protected, rawStorage.transaction(::protectedSnapshot), "after retrying $point")
            assertEquals(
                listOf(3L, 4L),
                rawStorage.transaction { it.intents(KILL_CLIENT_ID).map { row -> row.clientSequence } },
            )
        }
}

private suspend fun seedEffectsPending(storage: MutationJournalStorage) {
    storage.transaction { transaction ->
        transaction.insertClient(killClient())
        transaction.advanceClient(killClient(lastAllocated = 1L))
        insertKillIntent(transaction, sequence = 1L)
        transaction.insertExecution(killExecution(sequence = 1L))
        transaction.insertAttempt(killAttempt(sequence = 1L))
        transaction.insertEffect(killEffect(sequence = 1L))
        transaction.advanceExecution(
            killExecution(sequence = 1L, phase = MutationExecutionPhase.READY, generation = 1),
        )
        transaction.advanceExecution(
            killExecution(sequence = 1L, phase = MutationExecutionPhase.INFLIGHT, generation = 1),
        )
        transaction.insertAck(killAck(sequence = 1L))
        transaction.advanceExecution(
            killExecution(
                sequence = 1L,
                phase = MutationExecutionPhase.ACKED,
                generation = 1,
                attempt = 1,
                lastAttemptAt = 40L,
            ),
        )
        transaction.advanceExecution(
            killExecution(
                sequence = 1L,
                phase = MutationExecutionPhase.EFFECTS_PENDING,
                generation = 1,
                attempt = 1,
                lastAttemptAt = 40L,
            ),
        )
        transaction.insertAlias(killAlias())
    }
}

private suspend fun finalizeRetirement(storage: MutationJournalStorage) {
    storage.transaction { transaction ->
        transaction.advanceEffect(
            killEffect(
                sequence = 1L,
                disposition = MutationEffectDisposition.APPLIED,
                completedAt = 50L,
            ),
        )
        transaction.advanceAlias(killAlias(state = MutationAliasState.ACTIVE, activatedAt = 50L))
        transaction.advanceExecution(
            killExecution(
                sequence = 1L,
                phase = MutationExecutionPhase.RETIRED,
                generation = 1,
                attempt = 1,
                lastAttemptAt = 40L,
                retiredAt = 50L,
            ),
        )
        transaction.advanceClient(killClient(lastAllocated = 1L, retiredThrough = 1L))
    }
}

private suspend fun seedPrunableRows(storage: MutationJournalStorage) {
    storage.transaction { transaction ->
        transaction.insertClient(killClient())
        transaction.advanceClient(killClient(lastAllocated = 4L))
        (1L..4L).forEach { sequence ->
            insertKillIntent(transaction, sequence)
            transaction.insertExecution(killExecution(sequence))
        }
    }
    (1L..4L).forEach { sequence ->
        storage.transaction { transaction ->
            transaction.insertAttempt(killAttempt(sequence))
            transaction.insertEffect(killEffect(sequence))
            transaction.advanceExecution(
                killExecution(sequence, phase = MutationExecutionPhase.READY, generation = 1),
            )
            transaction.advanceExecution(
                killExecution(sequence, phase = MutationExecutionPhase.INFLIGHT, generation = 1),
            )
            transaction.insertAck(killAck(sequence))
            transaction.appendFailure(
                clientId = KILL_CLIENT_ID,
                clientSequence = sequence,
                generation = 1,
                kind = MutationFailureKind.EFFECT,
                detail = "retained-$sequence",
                message = "retained evidence $sequence",
                occurredAt = 40L + sequence,
            )
            transaction.advanceExecution(
                killExecution(
                    sequence,
                    phase = MutationExecutionPhase.ACKED,
                    generation = 1,
                    attempt = 1,
                    lastAttemptAt = 40L,
                ),
            )
            transaction.advanceExecution(
                killExecution(
                    sequence,
                    phase = MutationExecutionPhase.EFFECTS_PENDING,
                    generation = 1,
                    attempt = 1,
                    lastAttemptAt = 40L,
                ),
            )
            transaction.advanceEffect(
                killEffect(
                    sequence,
                    disposition = MutationEffectDisposition.APPLIED,
                    completedAt = 50L + sequence,
                ),
            )
            transaction.advanceExecution(
                killExecution(
                    sequence,
                    phase = MutationExecutionPhase.RETIRED,
                    generation = 1,
                    attempt = 1,
                    lastAttemptAt = 40L,
                    retiredAt = 50L + sequence,
                ),
            )
        }
    }
    storage.transaction { transaction ->
        transaction.advanceClient(killClient(lastAllocated = 4L, retiredThrough = 4L))
        transaction.confirmRetiredThrough(
            clientId = KILL_CLIENT_ID,
            requestedThroughSequence = 2L,
            serverConfirmedThroughSequence = 2L,
        )
        transaction.insertAlias(killAlias(sourceId = "alias-source", targetId = "alias-target"))
        transaction.advanceAlias(
            killAlias(
                sourceId = "alias-source",
                targetId = "alias-target",
                state = MutationAliasState.ACTIVE,
                activatedAt = 70L,
            ),
        )
        transaction.insertTombstone(killTombstone(sequence = 3L, canonicalId = "protected-active"))
        transaction.advanceTombstone(
            killTombstone(
                sequence = 3L,
                canonicalId = "protected-active",
                state = MutationTombstoneState.ACTIVE,
                activatedAt = 70L,
            ),
        )
        transaction.insertTombstone(killTombstone(sequence = 1L, canonicalId = "eligible-superseded"))
        transaction.advanceTombstone(
            killTombstone(
                sequence = 1L,
                canonicalId = "eligible-superseded",
                state = MutationTombstoneState.ACTIVE,
                activatedAt = 60L,
            ),
        )
        transaction.advanceTombstone(
            killTombstone(
                sequence = 1L,
                canonicalId = "eligible-superseded",
                state = MutationTombstoneState.SUPERSEDED,
                activatedAt = 60L,
                supersededBySequence = 2L,
                supersededAt = 65L,
            ),
        )
    }
}

private data class ProtectedSnapshot(
    val rows: List<String>,
)

private fun protectedSnapshot(transaction: MutationJournalTransaction): ProtectedSnapshot {
    val client = requireNotNull(transaction.client(KILL_CLIENT_ID))
    val rows = mutableListOf<String>()
    rows +=
        "client:${client.recordVersion}:${client.clientId}:${client.lastAllocatedSequence}:" +
        "${client.retiredThroughSequence}:${client.serverConfirmedRetiredThroughSequence}:${client.createdAt}"
    transaction.intents(KILL_CLIENT_ID).filter { it.clientSequence > 2L }.forEach { row ->
        rows +=
            "intent:${row.rowId}:${row.recordVersion}:${row.clientId}:${row.clientSequence}:" +
            "${row.mutationId}:${row.namespace}:${row.canonicalId}:${row.mutatorId}:" +
            "${row.mutatorVersion}:${row.argsBlob.toList()}:${row.idempotencyRoot}:${row.createdAt}"
    }
    transaction.executions(KILL_CLIENT_ID).filter { it.clientSequence > 2L }.forEach { row ->
        rows +=
            "execution:${row.clientId}:${row.clientSequence}:${row.phase}:${row.currentGeneration}:" +
            "${row.attempt}:${row.lastAttemptAt}:${row.activeFailureId}:${row.retiredAt}"
    }
    transaction.attempts(KILL_CLIENT_ID).filter { it.clientSequence > 2L }.forEach { row ->
        rows +=
            "attempt:${row.clientId}:${row.clientSequence}:${row.generation}:${row.effectiveNamespace}:" +
            "${row.effectiveCanonicalId}:${row.valueCodecVersion}:${row.basePresence}:${row.baseBlob?.toList()}:" +
            "${row.minePresence}:${row.mineBlob?.toList()}:${row.preconditionMetaPresent}:" +
            "${row.preconditionWrittenAt}:${row.preconditionEtag}:${row.advertisedRetiredThroughSequence}:" +
            "${row.generationIdempotencyKey}:${row.preparedAt}:${row.conflictMetaPresent}:" +
            "${row.conflictWrittenAt}:${row.conflictEtag}:${row.conflictReceivedAt}"
    }
    transaction.acks(KILL_CLIENT_ID).filter { it.clientSequence > 2L }.forEach { row ->
        rows +=
            "ack:${row.clientId}:${row.clientSequence}:${row.generation}:${row.authoritativePresence}:" +
            "${row.authoritativeBlob?.toList()}:${row.valueCodecVersion}:${row.etag}:" +
            "${row.canonicalTargetNamespace}:${row.canonicalTargetId}:${row.receivedAt}"
    }
    transaction.failures(KILL_CLIENT_ID).filter { it.clientSequence > 2L }.forEach { row ->
        rows +=
            "failure:${row.failureId}:${row.clientId}:${row.clientSequence}:${row.generation}:" +
            "${row.kind}:${row.detail}:${row.message}:${row.occurredAt}"
    }
    transaction.effects(KILL_CLIENT_ID).filter { it.clientSequence > 2L }.forEach { row ->
        rows +=
            "effect:${row.clientId}:${row.clientSequence}:${row.effectIndex}:${row.kind}:" +
            "${row.namespace}:${row.canonicalId}:${row.createdAt}:${row.disposition}:${row.completedAt}"
    }
    transaction.aliases().forEach { row ->
        rows +=
            "alias:${row.sourceNamespace}:${row.sourceCanonicalId}:${row.targetNamespace}:" +
            "${row.targetCanonicalId}:${row.state}:${row.createdByClientId}:${row.createdBySequence}:" +
            "${row.createdAt}:${row.activatedAt}"
    }
    transaction.tombstones().filter { it.state != MutationTombstoneState.SUPERSEDED }.forEach { row ->
        rows +=
            "tombstone:${row.namespace}:${row.canonicalId}:${row.createdByClientId}:" +
            "${row.createdBySequence}:${row.state}:${row.createdAt}:${row.activatedAt}:" +
            "${row.supersededByClientId}:${row.supersededBySequence}:${row.supersededAt}"
    }
    return ProtectedSnapshot(rows)
}

private fun insertKillIntent(
    transaction: MutationJournalTransaction,
    sequence: Long,
) {
    transaction.insertIntent(
        recordVersion = 1,
        clientId = KILL_CLIENT_ID,
        clientSequence = sequence,
        mutationId = "mutation-$sequence",
        namespace = "items",
        canonicalId = "item-$sequence",
        mutatorId = "mutator",
        mutatorVersion = 1,
        argsBlob = byteArrayOf(sequence.toByte()),
        idempotencyRoot = "root-$sequence",
        createdAt = 10L + sequence,
    )
}

private fun killClient(
    lastAllocated: Long = 0L,
    retiredThrough: Long = 0L,
): MutationClientRecord =
    MutationClientRecord(
        recordVersion = 1,
        clientId = KILL_CLIENT_ID,
        lastAllocatedSequence = lastAllocated,
        retiredThroughSequence = retiredThrough,
        serverConfirmedRetiredThroughSequence = 0L,
        createdAt = 1L,
    )

private fun killExecution(
    sequence: Long,
    phase: MutationExecutionPhase = MutationExecutionPhase.UNPREPARED,
    generation: Int = 0,
    attempt: Int = 0,
    lastAttemptAt: Long? = null,
    retiredAt: Long? = null,
): MutationExecutionRecord =
    MutationExecutionRecord(
        clientId = KILL_CLIENT_ID,
        clientSequence = sequence,
        phase = phase,
        currentGeneration = generation,
        attempt = attempt,
        lastAttemptAt = lastAttemptAt,
        activeFailureId = null,
        retiredAt = retiredAt,
    )

private fun killAttempt(sequence: Long): MutationAttemptRecord =
    MutationAttemptRecord(
        clientId = KILL_CLIENT_ID,
        clientSequence = sequence,
        generation = 1,
        effectiveNamespace = "items",
        effectiveCanonicalId = "item-$sequence",
        valueCodecVersion = 1,
        basePresence = MutationPresenceState.PRESENT,
        baseBlob = byteArrayOf(1),
        minePresence = MutationPresenceState.PRESENT,
        mineBlob = byteArrayOf(sequence.toByte()),
        preconditionMetaPresent = false,
        preconditionWrittenAt = null,
        preconditionEtag = null,
        advertisedRetiredThroughSequence = 0L,
        generationIdempotencyKey = "generation-$sequence-1",
        preparedAt = 20L + sequence,
        conflictMetaPresent = null,
        conflictWrittenAt = null,
        conflictEtag = null,
        conflictReceivedAt = null,
    )

private fun killAck(sequence: Long): MutationAckRecord =
    MutationAckRecord(
        clientId = KILL_CLIENT_ID,
        clientSequence = sequence,
        generation = 1,
        authoritativePresence = MutationPresenceState.PRESENT,
        authoritativeBlob = byteArrayOf(sequence.toByte()),
        valueCodecVersion = 1,
        etag = "etag-$sequence",
        canonicalTargetNamespace = null,
        canonicalTargetId = null,
        receivedAt = 40L + sequence,
    )

private fun killEffect(
    sequence: Long,
    disposition: MutationEffectDisposition = MutationEffectDisposition.PENDING,
    completedAt: Long? = null,
): MutationEffectRecord =
    MutationEffectRecord(
        clientId = KILL_CLIENT_ID,
        clientSequence = sequence,
        effectIndex = 0,
        kind = MutationEffectKind.KEY,
        namespace = "effects",
        canonicalId = "target-$sequence",
        createdAt = 30L + sequence,
        disposition = disposition,
        completedAt = completedAt,
    )

private fun killAlias(
    sourceId: String = "item-1",
    targetId: String = "canonical-1",
    state: MutationAliasState = MutationAliasState.PENDING,
    activatedAt: Long? = null,
): MutationKeyAliasRecord =
    MutationKeyAliasRecord(
        sourceNamespace = "items",
        sourceCanonicalId = sourceId,
        targetNamespace = "items",
        targetCanonicalId = targetId,
        state = state,
        createdByClientId = KILL_CLIENT_ID,
        createdBySequence = 1L,
        createdAt = 10L,
        activatedAt = activatedAt,
    )

private fun killTombstone(
    sequence: Long = 1L,
    canonicalId: String = "deleted-item",
    state: MutationTombstoneState = MutationTombstoneState.PENDING,
    activatedAt: Long? = null,
    supersededBySequence: Long? = null,
    supersededAt: Long? = null,
): MutationKeyTombstoneRecord =
    MutationKeyTombstoneRecord(
        namespace = "items",
        canonicalId = canonicalId,
        createdByClientId = KILL_CLIENT_ID,
        createdBySequence = sequence,
        state = state,
        createdAt = 10L,
        activatedAt = activatedAt,
        supersededByClientId = supersededBySequence?.let { KILL_CLIENT_ID },
        supersededBySequence = supersededBySequence,
        supersededAt = supersededAt,
    )

private const val KILL_CLIENT_ID: String = "kill-client"

private fun runKillPointTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
