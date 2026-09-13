@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.test.runTest
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
import org.mobilenativefoundation.store6.mutations.storage.MutationIntentRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyAliasRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyTombstoneRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationTombstoneState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

internal class SqlDelightJournalStoragePhysicalTest {
    @Test
    fun persistedEnumsUsePhysicalTextNamesNeverOrdinals() = runTest(timeout = 25.seconds) {
        withHarness { harness ->
            val storage = harness.storage()
            storage.transaction { transaction ->
                transaction.insertClient(client(lastAllocatedSequence = 0L))
                transaction.advanceClient(client(lastAllocatedSequence = 1L))
                transaction.insertIntent(
                    recordVersion = 1,
                    clientId = CLIENT_ID,
                    clientSequence = 1L,
                    mutationId = "mutation-1",
                    namespace = "items",
                    canonicalId = "one",
                    mutatorId = "upsert",
                    mutatorVersion = 1,
                    argsBlob = byteArrayOf(1),
                    idempotencyRoot = "client:1",
                    createdAt = 2L,
                )
                transaction.insertExecution(execution(MutationExecutionPhase.UNPREPARED, 0))
                transaction.insertAttempt(attempt())
                transaction.insertEffect(effect())
                transaction.advanceExecution(execution(MutationExecutionPhase.READY, 1))
                transaction.insertAck(ack())
                transaction.appendFailure(
                    clientId = CLIENT_ID,
                    clientSequence = 1L,
                    generation = 1,
                    kind = MutationFailureKind.PERSISTENCE,
                    detail = "detail",
                    message = "message",
                    occurredAt = 5L,
                )
                transaction.insertAlias(alias())
                transaction.insertTombstone(tombstone())
            }

            val expected =
                mapOf(
                    "store6_mutation_execution" to mapOf("phase" to "READY"),
                    "store6_mutation_attempt" to
                        mapOf("base_presence" to "ABSENT", "mine_presence" to "PRESENT"),
                    "store6_mutation_ack" to mapOf("authoritative_presence" to "PRESENT"),
                    "store6_mutation_failure" to mapOf("kind" to "PERSISTENCE"),
                    "store6_mutation_effect" to
                        mapOf("kind" to "KEY", "disposition" to "PENDING"),
                    "store6_key_alias" to mapOf("state" to "PENDING"),
                    "store6_key_tombstone" to mapOf("state" to "PENDING"),
                )
            expected.forEach { (table, columns) ->
                columns.forEach { (column, value) ->
                    assertEquals("text" to value, harness.storedText(table, column), "$table.$column")
                }
            }
        }
    }

    @Test
    fun outOfRangePersistedIntFailsClosed() = runTest(timeout = 25.seconds) {
        withHarness { harness ->
            val storage = harness.storage()
            harness.executeRaw(
                """INSERT INTO store6_mutation_execution(
                   client_id, client_sequence, phase, current_generation, attempt,
                   last_attempt_at, active_failure_id, retired_at)
                   VALUES ('client', 1, 'READY', 4294967297, 0, NULL, NULL, NULL)""",
            )

            assertFailsWith<IllegalStateException> {
                storage.transaction { it.executions(CLIENT_ID) }
            }
        }
    }

    @Test
    fun insertIntent_copiesArgsBeforeDriverInteraction() = runTest(timeout = 25.seconds) {
        val raw = freshJournalHarness()
        val args = byteArrayOf(1, 2, 3)
        val driver = IntentInsertHookDriver(raw.driver) { args[0] = 99 }
        val harness = JournalHarness(driver)
        try {
            val storage = harness.storage()
            lateinit var returned: MutationIntentRecord
            storage.transaction { transaction ->
                transaction.insertClient(client(lastAllocatedSequence = 0L))
                transaction.advanceClient(client(lastAllocatedSequence = 1L))
                returned =
                    transaction.insertIntent(
                        recordVersion = 1,
                        clientId = CLIENT_ID,
                        clientSequence = 1L,
                        mutationId = "mutation-1",
                        namespace = "items",
                        canonicalId = "one",
                        mutatorId = "upsert",
                        mutatorVersion = 1,
                        argsBlob = args,
                        idempotencyRoot = "client:1",
                        createdAt = 2L,
                    )
            }

            assertContentEquals(byteArrayOf(1, 2, 3), returned.argsBlob)
            assertContentEquals(
                byteArrayOf(1, 2, 3),
                storage.transaction { it.intents(CLIENT_ID).single().argsBlob },
            )
        } finally {
            driver.close()
        }
    }

    private suspend fun withHarness(block: suspend (JournalHarness) -> Unit) {
        val harness = freshJournalHarness()
        try {
            block(harness)
        } finally {
            harness.driver.close()
        }
    }

    private fun client(lastAllocatedSequence: Long): MutationClientRecord =
        MutationClientRecord(
            recordVersion = 1,
            clientId = CLIENT_ID,
            lastAllocatedSequence = lastAllocatedSequence,
            retiredThroughSequence = 0L,
            serverConfirmedRetiredThroughSequence = 0L,
            createdAt = 1L,
        )

    private fun execution(
        phase: MutationExecutionPhase,
        generation: Int,
    ): MutationExecutionRecord =
        MutationExecutionRecord(
            clientId = CLIENT_ID,
            clientSequence = 1L,
            phase = phase,
            currentGeneration = generation,
            attempt = 0,
            lastAttemptAt = null,
            activeFailureId = null,
            retiredAt = null,
        )

    private fun attempt(): MutationAttemptRecord =
        MutationAttemptRecord(
            clientId = CLIENT_ID,
            clientSequence = 1L,
            generation = 1,
            effectiveNamespace = "items",
            effectiveCanonicalId = "one",
            valueCodecVersion = 1,
            basePresence = MutationPresenceState.ABSENT,
            baseBlob = null,
            minePresence = MutationPresenceState.PRESENT,
            mineBlob = byteArrayOf(2),
            preconditionMetaPresent = false,
            preconditionWrittenAt = null,
            preconditionEtag = null,
            advertisedRetiredThroughSequence = 0L,
            generationIdempotencyKey = "client:1:g1",
            preparedAt = 3L,
            conflictMetaPresent = null,
            conflictWrittenAt = null,
            conflictEtag = null,
            conflictReceivedAt = null,
        )

    private fun ack(): MutationAckRecord =
        MutationAckRecord(
            clientId = CLIENT_ID,
            clientSequence = 1L,
            generation = 1,
            authoritativePresence = MutationPresenceState.PRESENT,
            authoritativeBlob = byteArrayOf(3),
            valueCodecVersion = 1,
            etag = null,
            canonicalTargetNamespace = null,
            canonicalTargetId = null,
            receivedAt = 4L,
        )

    private fun effect(): MutationEffectRecord =
        MutationEffectRecord(
            clientId = CLIENT_ID,
            clientSequence = 1L,
            effectIndex = 0,
            kind = MutationEffectKind.KEY,
            namespace = "items",
            canonicalId = "one",
            createdAt = 3L,
            disposition = MutationEffectDisposition.PENDING,
            completedAt = null,
        )

    private fun alias(): MutationKeyAliasRecord =
        MutationKeyAliasRecord(
            sourceNamespace = "items",
            sourceCanonicalId = "one",
            targetNamespace = "items",
            targetCanonicalId = "two",
            state = MutationAliasState.PENDING,
            createdByClientId = CLIENT_ID,
            createdBySequence = 1L,
            createdAt = 4L,
            activatedAt = null,
        )

    private fun tombstone(): MutationKeyTombstoneRecord =
        MutationKeyTombstoneRecord(
            namespace = "items",
            canonicalId = "one",
            createdByClientId = CLIENT_ID,
            createdBySequence = 1L,
            state = MutationTombstoneState.PENDING,
            createdAt = 4L,
            activatedAt = null,
            supersededByClientId = null,
            supersededBySequence = null,
            supersededAt = null,
        )

    private companion object {
        const val CLIENT_ID = "client"
    }
}

private class IntentInsertHookDriver(
    private val delegate: SqlDriver,
    private val beforeIntentInsert: () -> Unit,
) : SqlDriver by delegate {
    private var armed = true

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (armed && "INSERT INTO store6_mutation_intent" in sql) {
            armed = false
            beforeIntentInsert()
        }
        return delegate.execute(identifier, sql, parameters, binders)
    }
}
