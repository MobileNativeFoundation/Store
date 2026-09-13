@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations.sqldelight

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.mutations.MutationFailureKind
import org.mobilenativefoundation.store6.mutations.MutationPresenceState
import org.mobilenativefoundation.store6.mutations.sqldelight.internal.MutationJournalSidecar
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

/**
 * SQLite-backed mutation journal.
 *
 * [driver] and [transacter] must address the same connection/database authority. Store6 cannot
 * verify that pairing; violating it breaks transaction and storage-local ID guarantees. The
 * synchronous transaction boundary supports drivers whose raw operations return
 * [QueryResult.Value]; async web drivers are not supported by this adapter. Construct this
 * adapter before exposing the driver concurrently, and either dedicate the driver to mutation
 * journal adapters or externally serialize all other driver access. The adapter cannot coordinate
 * arbitrary SQL or adapter families outside this module.
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class SqlDelightMutationJournalStorage(
    private val driver: SqlDriver,
    private val transacter: Transacter,
) : MutationJournalStorage {
    @Suppress("unused")
    private val sidecar: MutationJournalSidecar = MutationJournalSidecar(driver, transacter)
    private val gate: Mutex = JournalDriverGates.forDriver(driver)

    public override suspend fun <R> transaction(
        block: (MutationJournalTransaction) -> R,
    ): R =
        gate.withLock {
            transacter.transactionWithResult {
                val transaction = SqlDelightMutationJournalTransaction(driver)
                try {
                    val result = block(transaction)
                    transaction.validateFinalState()
                    result
                } finally {
                    transaction.close()
                }
            }
        }
}

private object JournalDriverGates {
    private val gates: Array<Mutex> = Array(64) { Mutex() }

    fun forDriver(driver: SqlDriver): Mutex = gates[driver.hashCode() and (gates.size - 1)]
}

private class SqlDelightMutationJournalTransaction(
    private val driver: SqlDriver,
) : MutationJournalTransaction {
    private var active: Boolean = true
    private val clientTransitions: MutableList<ClientTransition> = mutableListOf()
    private val executionTransitions: MutableList<ExecutionTransition> = mutableListOf()
    private val aliasTransitions: MutableList<AliasTransition> = mutableListOf()
    private val tombstoneTransitions: MutableList<TombstoneTransition> = mutableListOf()

    fun close() {
        active = false
    }

    override fun client(clientId: String): MutationClientRecord? {
        requireActive()
        return queryOne(
            """SELECT record_version, client_id, last_allocated_sequence,
                      retired_through_sequence, server_confirmed_retired_through_sequence,
                      created_at
               FROM store6_mutation_client WHERE client_id = ?""",
            parameters = 1,
            binders = { bindString(0, clientId) },
        ) { cursor ->
            MutationClientRecord(
                recordVersion = cursor.requiredInt(0, "client record version"),
                clientId = cursor.requiredString(1),
                lastAllocatedSequence = cursor.requiredLong(2),
                retiredThroughSequence = cursor.requiredLong(3),
                serverConfirmedRetiredThroughSequence = cursor.requiredLong(4),
                createdAt = cursor.requiredLong(5),
            )
        }
    }

    override fun intents(clientId: String): List<MutationIntentRecord> {
        requireActive()
        return queryList(
            """SELECT row_id, record_version, client_id, client_sequence, mutation_id,
                      namespace, canonical_id, mutator_id, mutator_version, args_blob,
                      idempotency_root, created_at
               FROM store6_mutation_intent WHERE client_id = ? ORDER BY client_sequence""",
            parameters = 1,
            binders = { bindString(0, clientId) },
        ) { cursor -> cursor.toIntent() }
    }

    override fun executions(clientId: String): List<MutationExecutionRecord> {
        requireActive()
        return queryList(
            """SELECT client_id, client_sequence, phase, current_generation, attempt,
                      last_attempt_at, active_failure_id, retired_at
               FROM store6_mutation_execution WHERE client_id = ? ORDER BY client_sequence""",
            parameters = 1,
            binders = { bindString(0, clientId) },
        ) { cursor ->
            MutationExecutionRecord(
                clientId = cursor.requiredString(0),
                clientSequence = cursor.requiredLong(1),
                phase = enumNamed(cursor.requiredString(2), MutationExecutionPhase.entries, "execution phase"),
                currentGeneration = cursor.requiredInt(3, "execution generation"),
                attempt = cursor.requiredInt(4, "execution attempt"),
                lastAttemptAt = cursor.getLong(5),
                activeFailureId = cursor.getLong(6),
                retiredAt = cursor.getLong(7),
            )
        }
    }

    override fun attempts(clientId: String): List<MutationAttemptRecord> {
        requireActive()
        return queryList(
            """SELECT client_id, client_sequence, generation, effective_namespace,
                      effective_canonical_id, value_codec_version, base_presence, base_blob,
                      mine_presence, mine_blob, precondition_meta_present,
                      precondition_written_at, precondition_etag,
                      advertised_retired_through_sequence, generation_idempotency_key,
                      prepared_at, conflict_meta_present, conflict_written_at, conflict_etag,
                      conflict_received_at
               FROM store6_mutation_attempt WHERE client_id = ?
               ORDER BY client_sequence, generation""",
            parameters = 1,
            binders = { bindString(0, clientId) },
        ) { cursor -> cursor.toAttempt() }
    }

    override fun acks(clientId: String): List<MutationAckRecord> {
        requireActive()
        return queryList(
            """SELECT client_id, client_sequence, generation, authoritative_presence,
                      authoritative_blob, value_codec_version, etag,
                      canonical_target_namespace, canonical_target_id, received_at
               FROM store6_mutation_ack WHERE client_id = ?
               ORDER BY client_sequence, generation""",
            parameters = 1,
            binders = { bindString(0, clientId) },
        ) { cursor -> cursor.toAck() }
    }

    override fun failures(clientId: String): List<MutationFailureRecord> {
        requireActive()
        return queryList(
            """SELECT failure_id, client_id, client_sequence, generation, kind, detail,
                      message, occurred_at
               FROM store6_mutation_failure WHERE client_id = ? ORDER BY failure_id""",
            parameters = 1,
            binders = { bindString(0, clientId) },
        ) { cursor -> cursor.toFailure() }
    }

    override fun effects(clientId: String): List<MutationEffectRecord> {
        requireActive()
        return queryList(
            """SELECT client_id, client_sequence, effect_index, kind, namespace, canonical_id,
                      created_at, disposition, completed_at
               FROM store6_mutation_effect WHERE client_id = ?
               ORDER BY client_sequence, effect_index""",
            parameters = 1,
            binders = { bindString(0, clientId) },
        ) { cursor -> cursor.toEffect() }
    }

    override fun aliases(): List<MutationKeyAliasRecord> {
        requireActive()
        return queryList(
            """SELECT source_namespace, source_canonical_id, target_namespace,
                      target_canonical_id, state, created_by_client_id, created_by_sequence,
                      created_at, activated_at
               FROM store6_key_alias ORDER BY source_namespace, source_canonical_id""",
        ) { cursor -> cursor.toAlias() }
    }

    override fun tombstones(): List<MutationKeyTombstoneRecord> {
        requireActive()
        return queryList(
            """SELECT namespace, canonical_id, created_by_client_id, created_by_sequence,
                      state, created_at, activated_at, superseded_by_client_id,
                      superseded_by_sequence, superseded_at
               FROM store6_key_tombstone
               ORDER BY namespace, canonical_id, created_by_client_id, created_by_sequence""",
        ) { cursor -> cursor.toTombstone() }
    }

    override fun insertClient(record: MutationClientRecord) {
        requireActive()
        require(client(record.clientId) == null) { "client already exists: ${record.clientId}" }
        require(
            record.recordVersion == 1 &&
                record.lastAllocatedSequence == 0L &&
                record.retiredThroughSequence == 0L &&
                record.serverConfirmedRetiredThroughSequence == 0L,
        ) { "a new client must start at version 1 with zero allocation and retirement prefixes" }
        execute(
            """INSERT INTO store6_mutation_client(
               record_version, client_id, last_allocated_sequence, retired_through_sequence,
               server_confirmed_retired_through_sequence, created_at)
               VALUES (?, ?, ?, ?, ?, ?)""",
            6,
        ) {
            bindLong(0, record.recordVersion.toLong())
            bindString(1, record.clientId)
            bindLong(2, record.lastAllocatedSequence)
            bindLong(3, record.retiredThroughSequence)
            bindLong(4, record.serverConfirmedRetiredThroughSequence)
            bindLong(5, record.createdAt)
        }.requireOneRow("client insert")
    }

    override fun advanceClient(record: MutationClientRecord) {
        requireActive()
        val previous = requireNotNull(client(record.clientId)) {
            "client does not exist: ${record.clientId}"
        }
        require(record.recordVersion == previous.recordVersion) { "recordVersion is immutable" }
        require(record.createdAt == previous.createdAt) { "createdAt is immutable" }
        require(record.lastAllocatedSequence >= previous.lastAllocatedSequence) {
            "lastAllocatedSequence cannot regress"
        }
        require(record.retiredThroughSequence >= previous.retiredThroughSequence) {
            "retiredThroughSequence cannot regress"
        }
        require(
            record.serverConfirmedRetiredThroughSequence ==
                previous.serverConfirmedRetiredThroughSequence,
        ) { "checkpoint confirmation advances only through confirmRetiredThrough" }
        execute(
            """UPDATE store6_mutation_client
               SET last_allocated_sequence = ?, retired_through_sequence = ?
               WHERE client_id = ?""",
            3,
        ) {
            bindLong(0, record.lastAllocatedSequence)
            bindLong(1, record.retiredThroughSequence)
            bindString(2, record.clientId)
        }.requireOneRow("client advance")
        clientTransitions += ClientTransition(previous, record)
    }

    override fun confirmRetiredThrough(
        clientId: String,
        requestedThroughSequence: Long,
        serverConfirmedThroughSequence: Long,
    ): MutationClientRecord {
        requireActive()
        val previous = requireNotNull(client(clientId)) { "client does not exist: $clientId" }
        require(requestedThroughSequence >= 0L) { "requested checkpoint must be non-negative" }
        require(requestedThroughSequence <= previous.retiredThroughSequence) {
            "requested checkpoint cannot exceed the local retirement prefix"
        }
        require(serverConfirmedThroughSequence <= requestedThroughSequence) {
            "server confirmation cannot exceed the exact checkpoint request"
        }
        require(
            serverConfirmedThroughSequence >= previous.serverConfirmedRetiredThroughSequence,
        ) { "server confirmation cannot regress" }
        execute(
            """UPDATE store6_mutation_client
               SET server_confirmed_retired_through_sequence = ? WHERE client_id = ?""",
            2,
        ) {
            bindLong(0, serverConfirmedThroughSequence)
            bindString(1, clientId)
        }.requireOneRow("checkpoint confirmation")
        return MutationClientRecord(
            recordVersion = previous.recordVersion,
            clientId = previous.clientId,
            lastAllocatedSequence = previous.lastAllocatedSequence,
            retiredThroughSequence = previous.retiredThroughSequence,
            serverConfirmedRetiredThroughSequence = serverConfirmedThroughSequence,
            createdAt = previous.createdAt,
        )
    }

    override fun insertIntent(
        recordVersion: Int,
        clientId: String,
        clientSequence: Long,
        mutationId: String,
        namespace: String,
        canonicalId: String,
        mutatorId: String,
        mutatorVersion: Int,
        argsBlob: ByteArray,
        idempotencyRoot: String,
        createdAt: Long,
    ): MutationIntentRecord {
        requireActive()
        val accepted = MutationIntentRecord(
            rowId = 0L,
            recordVersion = recordVersion,
            clientId = clientId,
            clientSequence = clientSequence,
            mutationId = mutationId,
            namespace = namespace,
            canonicalId = canonicalId,
            mutatorId = mutatorId,
            mutatorVersion = mutatorVersion,
            argsBlob = argsBlob,
            idempotencyRoot = idempotencyRoot,
            createdAt = createdAt,
        )
        val owner = requireNotNull(client(accepted.clientId)) {
            "client does not exist: ${accepted.clientId}"
        }
        require(accepted.clientSequence > owner.retiredThroughSequence) {
            "intent sequence must be newer than the retired client prefix"
        }
        require(accepted.clientSequence <= owner.lastAllocatedSequence) {
            "intent sequence exceeds the allocated client sequence"
        }
        val existing = intents(accepted.clientId)
        require(existing.none { it.clientSequence == accepted.clientSequence }) {
            "intent sequence already exists: ${accepted.clientSequence}"
        }
        require(existing.none { it.mutationId == accepted.mutationId }) {
            "mutationId already exists for client: ${accepted.mutationId}"
        }
        require(
            !exists(
                "SELECT 1 FROM store6_mutation_intent WHERE idempotency_root = ?",
                1,
            ) { bindString(0, accepted.idempotencyRoot) },
        ) { "idempotencyRoot already exists: ${accepted.idempotencyRoot}" }
        execute(
            """INSERT INTO store6_mutation_intent(
               record_version, client_id, client_sequence, mutation_id, namespace, canonical_id,
               mutator_id, mutator_version, args_blob, idempotency_root, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            11,
        ) {
            bindLong(0, accepted.recordVersion.toLong())
            bindString(1, accepted.clientId)
            bindLong(2, accepted.clientSequence)
            bindString(3, accepted.mutationId)
            bindString(4, accepted.namespace)
            bindString(5, accepted.canonicalId)
            bindString(6, accepted.mutatorId)
            bindLong(7, accepted.mutatorVersion.toLong())
            bindBytes(8, accepted.argsBlob)
            bindString(9, accepted.idempotencyRoot)
            bindLong(10, accepted.createdAt)
        }.requireOneRow("intent insert")
        val rowId = requireNotNull(queryLong("SELECT last_insert_rowid()"))
        return MutationIntentRecord(
            rowId = rowId,
            recordVersion = accepted.recordVersion,
            clientId = accepted.clientId,
            clientSequence = accepted.clientSequence,
            mutationId = accepted.mutationId,
            namespace = accepted.namespace,
            canonicalId = accepted.canonicalId,
            mutatorId = accepted.mutatorId,
            mutatorVersion = accepted.mutatorVersion,
            argsBlob = accepted.argsBlob,
            idempotencyRoot = accepted.idempotencyRoot,
            createdAt = accepted.createdAt,
        )
    }

    override fun insertExecution(record: MutationExecutionRecord) {
        requireActive()
        require(intents(record.clientId).any { it.clientSequence == record.clientSequence }) {
            "execution requires an intent"
        }
        require(executions(record.clientId).none { it.clientSequence == record.clientSequence }) {
            "execution already exists"
        }
        require(record.phase == MutationExecutionPhase.UNPREPARED) {
            "new execution must start UNPREPARED"
        }
        execute(
            """INSERT INTO store6_mutation_execution(
               client_id, client_sequence, phase, current_generation, attempt, last_attempt_at,
               active_failure_id, retired_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            8,
        ) {
            bindString(0, record.clientId)
            bindLong(1, record.clientSequence)
            bindString(2, record.phase.name)
            bindLong(3, record.currentGeneration.toLong())
            bindLong(4, record.attempt.toLong())
            bindLong(5, record.lastAttemptAt)
            bindLong(6, record.activeFailureId)
            bindLong(7, record.retiredAt)
        }.requireOneRow("execution insert")
    }

    override fun advanceExecution(record: MutationExecutionRecord) {
        requireActive()
        val previous =
            requireNotNull(executions(record.clientId).firstOrNull {
                it.clientSequence == record.clientSequence
            }) { "execution does not exist" }
        if (previous.sameValueAs(record)) return
        require(record.currentGeneration >= previous.currentGeneration) {
            "currentGeneration cannot regress"
        }
        val activeFailureKind =
            if (record.phase == MutationExecutionPhase.PARKED) {
                val activeFailureId = requireNotNull(record.activeFailureId)
                val failure =
                    failures(record.clientId).firstOrNull { it.failureId == activeFailureId }
                requireNotNull(failure) { "PARKED requires its active failure row" }
                require(
                    failure.clientId == record.clientId &&
                        failure.clientSequence == record.clientSequence &&
                        failure.generation == record.currentGeneration,
                ) { "active failure identity does not match the execution" }
                failure.kind
            } else {
                null
            }
        requireExecutionTransition(previous, record, activeFailureKind)
        if (record.currentGeneration > 0) {
            require(attempts(record.clientId).any {
                it.clientSequence == record.clientSequence &&
                    it.generation == record.currentGeneration
            }) { "execution generation requires an immutable attempt row" }
        }
        val generationContinuation =
            previous.phase == MutationExecutionPhase.REFRESH_REQUIRED &&
                record.phase == MutationExecutionPhase.READY &&
                record.currentGeneration == previous.currentGeneration + 1
        if (generationContinuation) {
            val durableAttempts = attempts(record.clientId)
            val previousAttempt =
                requireNotNull(
                    durableAttempts.singleOrNull {
                        it.clientSequence == record.clientSequence &&
                            it.generation == previous.currentGeneration
                    },
                ) { "generation continuation requires the previous immutable attempt" }
            val nextAttempt =
                requireNotNull(
                    durableAttempts.singleOrNull {
                        it.clientSequence == record.clientSequence &&
                            it.generation == record.currentGeneration
                    },
                ) { "generation continuation requires the next immutable attempt" }
            require(isUniqueNamespaceOwner(previous)) {
                "generation continuation requires the previous unique namespace owner"
            }
            requireNamespaceAuthorityAvailable(record)
            require(
                previousAttempt.effectiveNamespace == nextAttempt.effectiveNamespace &&
                    previousAttempt.effectiveCanonicalId == nextAttempt.effectiveCanonicalId,
            ) { "generation continuation must preserve exact effective identity" }
        }
        if (record.phase == MutationExecutionPhase.ACKED) {
            require(acks(record.clientId).any {
                it.clientSequence == record.clientSequence &&
                    it.generation == record.currentGeneration
            }) { "ACKED requires its matching acknowledgement" }
        }
        if (record.phase == MutationExecutionPhase.RETIRED) {
            require(effects(record.clientId).none {
                it.clientSequence == record.clientSequence &&
                    it.disposition == MutationEffectDisposition.PENDING
            }) { "an execution cannot retire with a pending effect" }
        }
        if (
            previous.phase == MutationExecutionPhase.READY &&
            record.phase == MutationExecutionPhase.INFLIGHT
        ) {
            requireNamespaceAuthorityAvailable(record)
        }
        val wasUniqueNamespaceOwner =
            previous.phase == MutationExecutionPhase.EFFECTS_PENDING &&
                record.phase == MutationExecutionPhase.RETIRED &&
                isUniqueNamespaceOwner(previous)
        execute(
            """UPDATE store6_mutation_execution
               SET phase = ?, current_generation = ?, attempt = ?, last_attempt_at = ?,
                   active_failure_id = ?, retired_at = ?
               WHERE client_id = ? AND client_sequence = ?""",
            8,
        ) {
            bindString(0, record.phase.name)
            bindLong(1, record.currentGeneration.toLong())
            bindLong(2, record.attempt.toLong())
            bindLong(3, record.lastAttemptAt)
            bindLong(4, record.activeFailureId)
            bindLong(5, record.retiredAt)
            bindString(6, record.clientId)
            bindLong(7, record.clientSequence)
        }.requireOneRow("execution advance")
        executionTransitions +=
            ExecutionTransition(
                previous = previous,
                next = record,
                wasUniqueNamespaceOwner = wasUniqueNamespaceOwner,
            )
    }

    override fun insertAttempt(record: MutationAttemptRecord) {
        requireActive()
        require(intents(record.clientId).any { it.clientSequence == record.clientSequence }) {
            "attempt requires an intent"
        }
        require(attempts(record.clientId).none {
            it.clientSequence == record.clientSequence && it.generation == record.generation
        }) { "attempt generation already exists" }
        require(
            !exists(
                "SELECT 1 FROM store6_mutation_attempt WHERE generation_idempotency_key = ?",
                1,
            ) { bindString(0, record.generationIdempotencyKey) },
        ) { "generationIdempotencyKey already exists" }
        require(record.conflictMetaPresent == null) {
            "a new attempt cannot contain a conflict receipt"
        }
        execute(
            """INSERT INTO store6_mutation_attempt(
               client_id, client_sequence, generation, effective_namespace,
               effective_canonical_id, value_codec_version, base_presence, base_blob,
               mine_presence, mine_blob, precondition_meta_present, precondition_written_at,
               precondition_etag, advertised_retired_through_sequence,
               generation_idempotency_key, prepared_at, conflict_meta_present,
               conflict_written_at, conflict_etag, conflict_received_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            20,
        ) {
            bindAttempt(record)
        }.requireOneRow("attempt insert")
    }

    override fun recordConflictReceipt(record: MutationAttemptRecord) {
        requireActive()
        val previous =
            requireNotNull(attempts(record.clientId).firstOrNull {
                it.clientSequence == record.clientSequence && it.generation == record.generation
            }) { "attempt does not exist" }
        require(previous.samePreparationAs(record)) { "attempt preparation fields are immutable" }
        require(record.conflictMetaPresent != null && record.conflictReceivedAt != null) {
            "conflict receipt is incomplete"
        }
        if (previous.sameValueAs(record)) return
        require(previous.conflictMetaPresent == null) { "conflict receipt is write-once" }
        execute(
            """UPDATE store6_mutation_attempt
               SET conflict_meta_present = ?, conflict_written_at = ?, conflict_etag = ?,
                   conflict_received_at = ?
               WHERE client_id = ? AND client_sequence = ? AND generation = ?""",
            7,
        ) {
            bindBoolean(0, record.conflictMetaPresent)
            bindLong(1, record.conflictWrittenAt)
            bindString(2, record.conflictEtag)
            bindLong(3, record.conflictReceivedAt)
            bindString(4, record.clientId)
            bindLong(5, record.clientSequence)
            bindLong(6, record.generation.toLong())
        }.requireOneRow("conflict receipt")
    }

    override fun insertAck(record: MutationAckRecord) {
        requireActive()
        val attempt =
            requireNotNull(attempts(record.clientId).firstOrNull {
                it.clientSequence == record.clientSequence && it.generation == record.generation
            }) { "acknowledgement requires its attempt generation" }
        if (record.canonicalTargetNamespace != null) {
            require(record.canonicalTargetNamespace == attempt.effectiveNamespace) {
                "canonical acknowledgement target cannot cross namespaces"
            }
        }
        val previous = acks(record.clientId).firstOrNull {
            it.clientSequence == record.clientSequence && it.generation == record.generation
        }
        if (previous != null) {
            require(previous.sameValueAs(record)) { "acknowledgement is write-once" }
            return
        }
        execute(
            """INSERT INTO store6_mutation_ack(
               client_id, client_sequence, generation, authoritative_presence,
               authoritative_blob, value_codec_version, etag, canonical_target_namespace,
               canonical_target_id, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            10,
        ) {
            bindString(0, record.clientId)
            bindLong(1, record.clientSequence)
            bindLong(2, record.generation.toLong())
            bindString(3, record.authoritativePresence.name)
            bindBytes(4, record.authoritativeBlob?.copyOf())
            bindLong(5, record.valueCodecVersion.toLong())
            bindString(6, record.etag)
            bindString(7, record.canonicalTargetNamespace)
            bindString(8, record.canonicalTargetId)
            bindLong(9, record.receivedAt)
        }.requireOneRow("acknowledgement insert")
    }

    override fun appendFailure(
        clientId: String,
        clientSequence: Long,
        generation: Int,
        kind: MutationFailureKind,
        detail: String,
        message: String,
        occurredAt: Long,
    ): MutationFailureRecord {
        requireActive()
        require(intents(clientId).any { it.clientSequence == clientSequence }) {
            "failure requires an intent"
        }
        val boundedDetail = detail.truncateUtf8(128)
        val boundedMessage = message.truncateUtf8(1_024)
        MutationFailureRecord(
            0L, clientId, clientSequence, generation, kind, boundedDetail, boundedMessage, occurredAt,
        )
        execute(
            """INSERT INTO store6_mutation_failure(
               client_id, client_sequence, generation, kind, detail, message, occurred_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            7,
        ) {
            bindString(0, clientId)
            bindLong(1, clientSequence)
            bindLong(2, generation.toLong())
            bindString(3, kind.name)
            bindString(4, boundedDetail)
            bindString(5, boundedMessage)
            bindLong(6, occurredAt)
        }.requireOneRow("failure insert")
        return MutationFailureRecord(
            failureId = requireNotNull(queryLong("SELECT last_insert_rowid()")),
            clientId = clientId,
            clientSequence = clientSequence,
            generation = generation,
            kind = kind,
            detail = boundedDetail,
            message = boundedMessage,
            occurredAt = occurredAt,
        )
    }

    override fun insertEffect(record: MutationEffectRecord) {
        requireActive()
        require(intents(record.clientId).any { it.clientSequence == record.clientSequence }) {
            "effect requires an intent"
        }
        require(record.disposition == MutationEffectDisposition.PENDING) {
            "new effect must start PENDING"
        }
        require(effects(record.clientId).none {
            it.clientSequence == record.clientSequence && it.effectIndex == record.effectIndex
        }) { "effect identity already exists" }
        execute(
            """INSERT INTO store6_mutation_effect(
               client_id, client_sequence, effect_index, kind, namespace, canonical_id,
               created_at, disposition, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            9,
        ) {
            bindString(0, record.clientId)
            bindLong(1, record.clientSequence)
            bindLong(2, record.effectIndex.toLong())
            bindString(3, record.kind.name)
            bindString(4, record.namespace)
            bindString(5, record.canonicalId)
            bindLong(6, record.createdAt)
            bindString(7, record.disposition.name)
            bindLong(8, record.completedAt)
        }.requireOneRow("effect insert")
    }

    override fun advanceEffect(record: MutationEffectRecord) {
        requireActive()
        val previous =
            requireNotNull(effects(record.clientId).firstOrNull {
                it.clientSequence == record.clientSequence && it.effectIndex == record.effectIndex
            }) { "effect does not exist" }
        if (previous.sameValueAs(record)) return
        require(previous.sameIdentityAndTargetAs(record)) { "effect identity and target are immutable" }
        require(previous.disposition == MutationEffectDisposition.PENDING) {
            "terminal effect disposition cannot change"
        }
        require(record.disposition != MutationEffectDisposition.PENDING) {
            "effect disposition cannot regress to PENDING"
        }
        execute(
            """UPDATE store6_mutation_effect SET disposition = ?, completed_at = ?
               WHERE client_id = ? AND client_sequence = ? AND effect_index = ?""",
            5,
        ) {
            bindString(0, record.disposition.name)
            bindLong(1, record.completedAt)
            bindString(2, record.clientId)
            bindLong(3, record.clientSequence)
            bindLong(4, record.effectIndex.toLong())
        }.requireOneRow("effect advance")
    }

    override fun insertAlias(record: MutationKeyAliasRecord) {
        requireActive()
        require(record.state == MutationAliasState.PENDING) { "new alias must start PENDING" }
        val previous = aliases().firstOrNull {
            it.sourceNamespace == record.sourceNamespace &&
                it.sourceCanonicalId == record.sourceCanonicalId
        }
        if (previous != null) {
            require(previous.sameRouteAs(record)) { "alias source cannot be retargeted" }
            return
        }
        require(!wouldCreateAliasCycle(record)) { "alias edge would create a cycle" }
        execute(
            """INSERT INTO store6_key_alias(
               source_namespace, source_canonical_id, target_namespace, target_canonical_id,
               state, created_by_client_id, created_by_sequence, created_at, activated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            9,
        ) {
            bindString(0, record.sourceNamespace)
            bindString(1, record.sourceCanonicalId)
            bindString(2, record.targetNamespace)
            bindString(3, record.targetCanonicalId)
            bindString(4, record.state.name)
            bindString(5, record.createdByClientId)
            bindLong(6, record.createdBySequence)
            bindLong(7, record.createdAt)
            bindLong(8, record.activatedAt)
        }.requireOneRow("alias insert")
    }

    override fun advanceAlias(record: MutationKeyAliasRecord) {
        requireActive()
        val previous =
            requireNotNull(aliases().firstOrNull {
                it.sourceNamespace == record.sourceNamespace &&
                    it.sourceCanonicalId == record.sourceCanonicalId
            }) { "alias does not exist" }
        if (previous.sameValueAs(record)) return
        require(previous.sameEdgeAs(record)) { "alias edge and creator are immutable" }
        require(
            previous.state == MutationAliasState.PENDING && record.state == MutationAliasState.ACTIVE,
        ) { "alias state can only advance PENDING to ACTIVE" }
        execute(
            """UPDATE store6_key_alias SET state = ?, activated_at = ?
               WHERE source_namespace = ? AND source_canonical_id = ?""",
            4,
        ) {
            bindString(0, record.state.name)
            bindLong(1, record.activatedAt)
            bindString(2, record.sourceNamespace)
            bindString(3, record.sourceCanonicalId)
        }.requireOneRow("alias advance")
        aliasTransitions += AliasTransition(previous, record)
    }

    override fun insertTombstone(record: MutationKeyTombstoneRecord) {
        requireActive()
        require(record.state == MutationTombstoneState.PENDING) {
            "new tombstone must start PENDING"
        }
        val previous = tombstones().firstOrNull { it.sameKeyAs(record) }
        if (previous != null) {
            require(previous.sameValueAs(record)) { "tombstone generation is immutable" }
            return
        }
        requireNoOtherTombstoneInState(record, MutationTombstoneState.PENDING, excluding = null)
        insertTombstoneRow(record)
    }

    override fun advanceTombstone(record: MutationKeyTombstoneRecord) {
        requireActive()
        val previous = requireNotNull(tombstones().firstOrNull { it.sameKeyAs(record) }) {
            "tombstone generation does not exist"
        }
        if (previous.sameValueAs(record)) return
        require(previous.sameGenerationAs(record)) { "tombstone generation identity is immutable" }
        val legal =
            (previous.state == MutationTombstoneState.PENDING &&
                record.state == MutationTombstoneState.ACTIVE) ||
                (previous.state == MutationTombstoneState.ACTIVE &&
                    record.state == MutationTombstoneState.SUPERSEDED)
        require(legal) { "tombstone state must advance PENDING to ACTIVE to SUPERSEDED" }
        if (
            previous.state == MutationTombstoneState.ACTIVE &&
            record.state == MutationTombstoneState.SUPERSEDED
        ) {
            require(record.activatedAt == previous.activatedAt) {
                "tombstone activation timestamp is immutable after activation"
            }
            if (record.supersededByClientId == previous.createdByClientId) {
                require(record.supersededBySequence != previous.createdBySequence) {
                    "a same-client tombstone successor must be causally distinct"
                }
            }
        }
        requireNoOtherTombstoneInState(record, record.state, excluding = record)
        execute(
            """UPDATE store6_key_tombstone
               SET state = ?, activated_at = ?, superseded_by_client_id = ?,
                   superseded_by_sequence = ?, superseded_at = ?
               WHERE namespace = ? AND canonical_id = ? AND created_by_client_id = ?
                 AND created_by_sequence = ?""",
            9,
        ) {
            bindString(0, record.state.name)
            bindLong(1, record.activatedAt)
            bindString(2, record.supersededByClientId)
            bindLong(3, record.supersededBySequence)
            bindLong(4, record.supersededAt)
            bindString(5, record.namespace)
            bindString(6, record.canonicalId)
            bindString(7, record.createdByClientId)
            bindLong(8, record.createdBySequence)
        }.requireOneRow("tombstone advance")
        tombstoneTransitions += TombstoneTransition(previous, record)
    }

    override fun prune(
        clientId: String,
        serverConfirmedRetiredThroughSequence: Long,
    ) {
        requireActive()
        val owner = requireNotNull(client(clientId)) { "client does not exist: $clientId" }
        require(serverConfirmedRetiredThroughSequence >= 0L) { "prune prefix must be non-negative" }
        require(
            serverConfirmedRetiredThroughSequence <=
                owner.serverConfirmedRetiredThroughSequence,
        ) { "prune prefix exceeds the persisted server-confirmed prefix" }

        val removableTombstones =
            tombstones().filter { tombstone ->
                tombstone.createdByClientId == clientId &&
                    tombstone.createdBySequence <= serverConfirmedRetiredThroughSequence &&
                    tombstone.state == MutationTombstoneState.SUPERSEDED &&
                    tombstone.supersedingIntentIsConfirmed()
            }
        listOf(
            "store6_mutation_effect",
            "store6_mutation_ack",
            "store6_mutation_failure",
            "store6_mutation_attempt",
            "store6_mutation_execution",
            "store6_mutation_intent",
        ).forEach { table ->
            execute(
                "DELETE FROM $table WHERE client_id = ? AND client_sequence <= ?",
                2,
            ) {
                bindString(0, clientId)
                bindLong(1, serverConfirmedRetiredThroughSequence)
            }
        }
        removableTombstones.forEach { tombstone ->
            execute(
                """DELETE FROM store6_key_tombstone
                   WHERE namespace = ? AND canonical_id = ? AND created_by_client_id = ?
                     AND created_by_sequence = ?""",
                4,
            ) {
                bindString(0, tombstone.namespace)
                bindString(1, tombstone.canonicalId)
                bindString(2, tombstone.createdByClientId)
                bindLong(3, tombstone.createdBySequence)
            }.requireOneRow("tombstone prune")
        }
    }

    fun validateFinalState() {
        requireActive()
        val lowerSuccessors =
            tombstoneTransitions.filter { transition -> transition.isSameClientLowerSuccessor() }
        if (lowerSuccessors.isEmpty()) return

        lowerSuccessors
            .groupBy { transition ->
                SuccessorKey(
                    clientId = requireNotNull(transition.next.supersededByClientId),
                    sequence = requireNotNull(transition.next.supersededBySequence),
                )
            }.forEach { (successor, predecessors) ->
                validateCausalLowerSuccessor(successor, predecessors)
            }
    }

    private fun validateCausalLowerSuccessor(
        successor: SuccessorKey,
        predecessors: List<TombstoneTransition>,
    ) {
        require(
            predecessors.all { transition ->
                transition.previous.state == MutationTombstoneState.ACTIVE &&
                    transition.next.state == MutationTombstoneState.SUPERSEDED &&
                    transition.previous.activatedAt == transition.next.activatedAt &&
                    transition.next.supersededByClientId == successor.clientId &&
                    transition.next.supersededBySequence == successor.sequence
            },
        ) { "a lower causal successor must preserve every ACTIVE predecessor exactly" }

        val retirement =
            executionTransitions.singleOrNull { transition ->
                transition.previous.clientId == successor.clientId &&
                    transition.previous.clientSequence == successor.sequence &&
                    transition.previous.phase == MutationExecutionPhase.EFFECTS_PENDING &&
                    transition.next.phase == MutationExecutionPhase.RETIRED
            }
        require(retirement != null && retirement.wasUniqueNamespaceOwner) {
            "a lower causal successor must retire from unique EFFECTS_PENDING authority"
        }

        val finalExecutions = executions(successor.clientId)
        val successorExecution =
            finalExecutions.firstOrNull { execution ->
                execution.clientSequence == successor.sequence
            }
        require(
            successorExecution != null &&
                successorExecution.phase == MutationExecutionPhase.RETIRED &&
                successorExecution.currentGeneration == retirement.previous.currentGeneration,
        ) { "a lower causal successor must commit its exact RETIRED execution" }

        val attemptKey =
            AttemptKey(
                clientId = successor.clientId,
                sequence = successor.sequence,
                generation = retirement.previous.currentGeneration,
            )
        val finalAttempts = attempts(successor.clientId).associateBy { attempt -> attempt.key() }
        val attempt = requireNotNull(finalAttempts[attemptKey]) {
            "a lower causal successor requires its exact attempt"
        }
        val acknowledgement =
            requireNotNull(
                acks(successor.clientId).firstOrNull { acknowledgement ->
                    acknowledgement.clientSequence == successor.sequence &&
                        acknowledgement.generation == attempt.generation
                },
            ) { "a lower causal successor requires its exact acknowledgement" }
        require(
            acknowledgement.authoritativePresence == MutationPresenceState.PRESENT &&
                acknowledgement.canonicalTargetNamespace != null &&
                acknowledgement.canonicalTargetId != null,
        ) { "a lower causal successor requires a PRESENT canonical acknowledgement" }
        require(
            effects(successor.clientId).none { effect ->
                effect.clientSequence == successor.sequence &&
                    effect.disposition == MutationEffectDisposition.PENDING
            },
        ) { "a lower causal successor cannot commit with a pending effect" }

        val aliasTransition =
            aliasTransitions.singleOrNull { transition ->
                transition.previous.state == MutationAliasState.PENDING &&
                    transition.next.state == MutationAliasState.ACTIVE &&
                    transition.next.createdByClientId == successor.clientId &&
                    transition.next.createdBySequence == successor.sequence &&
                    transition.next.sourceNamespace == attempt.effectiveNamespace &&
                    transition.next.sourceCanonicalId == attempt.effectiveCanonicalId &&
                    transition.next.targetNamespace == acknowledgement.canonicalTargetNamespace &&
                    transition.next.targetCanonicalId == acknowledgement.canonicalTargetId
            }
        require(aliasTransition != null) {
            "a lower causal successor must activate its exact acknowledged PENDING alias"
        }
        val finalAliases = aliases()
        val finalAlias =
            finalAliases.firstOrNull { alias ->
                alias.sourceNamespace == aliasTransition.next.sourceNamespace &&
                    alias.sourceCanonicalId == aliasTransition.next.sourceCanonicalId
            }
        require(finalAlias != null && finalAlias.sameValueAs(aliasTransition.next)) {
            "a lower causal successor must commit its activated alias"
        }

        val route =
            activeRouteFrom(
                source = IdentityKey(attempt.effectiveNamespace, attempt.effectiveCanonicalId),
                aliases = finalAliases,
            )
        require(
            predecessors.all { transition ->
                IdentityKey(transition.previous.namespace, transition.previous.canonicalId) in route
            },
        ) { "a lower causal successor route must contain every predecessor" }

        val finalTombstones = tombstones()
        val collapsedTransitions =
            tombstoneTransitions.filter { transition ->
                transition.previous.state == MutationTombstoneState.ACTIVE &&
                    IdentityKey(
                        transition.previous.namespace,
                        transition.previous.canonicalId,
                    ) in route
            }
        require(
            collapsedTransitions.all { transition ->
                transition.next.state == MutationTombstoneState.SUPERSEDED &&
                    transition.next.supersededByClientId == successor.clientId &&
                    transition.next.supersededBySequence == successor.sequence &&
                    transition.previous.activatedAt == transition.next.activatedAt &&
                    finalTombstones.firstOrNull { it.sameKeyAs(transition.next) }
                        ?.sameValueAs(transition.next) == true
            },
        ) { "every collapsed ACTIVE tombstone must name the exact causal successor" }
        require(
            finalTombstones.none { tombstone ->
                tombstone.state == MutationTombstoneState.ACTIVE &&
                    IdentityKey(tombstone.namespace, tombstone.canonicalId) in route
            },
        ) { "a lower causal successor must collapse every ACTIVE tombstone in its route" }

        requireTruthfulPrefixAdvance(successor.clientId, finalExecutions)
        require(
            finalExecutions.none { execution ->
                execution.ownsNamespaceAuthority() &&
                    namespaceFor(execution, finalAttempts) == attempt.effectiveNamespace
            },
        ) { "a lower causal successor must release namespace authority in the same commit" }
    }

    private fun requireTruthfulPrefixAdvance(
        clientId: String,
        finalExecutions: List<MutationExecutionRecord>,
    ) {
        val prefixTransitions =
            clientTransitions.filter { transition ->
                transition.previous.clientId == clientId &&
                    transition.next.retiredThroughSequence >
                    transition.previous.retiredThroughSequence
            }
        require(prefixTransitions.isNotEmpty()) {
            "a lower causal successor must advance the client retirement prefix"
        }
        val startingPrefix = prefixTransitions.first().previous.retiredThroughSequence
        val finalClient = requireNotNull(client(clientId))
        val executionsBySequence = finalExecutions.associateBy { it.clientSequence }
        var truthfulPrefix = startingPrefix
        while (truthfulPrefix < finalClient.lastAllocatedSequence) {
            val next = executionsBySequence[truthfulPrefix + 1L] ?: break
            if (next.phase != MutationExecutionPhase.RETIRED) break
            truthfulPrefix += 1L
        }
        require(
            finalClient.retiredThroughSequence > startingPrefix &&
                finalClient.retiredThroughSequence == truthfulPrefix,
        ) { "a lower causal successor requires an exact truthful retirement-prefix advance" }
    }

    private fun requireNamespaceAuthorityAvailable(candidate: MutationExecutionRecord) {
        val allAttempts = attempts(candidate.clientId).associateBy { attempt -> attempt.key() }
        val namespace = namespaceFor(candidate, allAttempts)
        require(
            executions(candidate.clientId).none { execution ->
                execution.clientSequence != candidate.clientSequence &&
                    execution.ownsNamespaceAuthority() &&
                    namespaceFor(execution, allAttempts) == namespace
            },
        ) { "namespace authority already has an owner" }
    }

    private fun isUniqueNamespaceOwner(candidate: MutationExecutionRecord): Boolean {
        if (!candidate.ownsNamespaceAuthority()) return false
        val allAttempts = attempts(candidate.clientId).associateBy { attempt -> attempt.key() }
        val namespace = namespaceFor(candidate, allAttempts)
        val owners =
            executions(candidate.clientId).filter { execution ->
                execution.ownsNamespaceAuthority() &&
                    namespaceFor(execution, allAttempts) == namespace
            }
        return owners.size == 1 &&
            owners.single().clientSequence == candidate.clientSequence
    }

    private fun namespaceFor(
        execution: MutationExecutionRecord,
        attempts: Map<AttemptKey, MutationAttemptRecord>,
    ): String =
        requireNotNull(
            attempts[
                AttemptKey(
                    execution.clientId,
                    execution.clientSequence,
                    execution.currentGeneration,
                )
            ],
        ) { "namespace authority requires the exact current attempt" }.effectiveNamespace

    private fun activeRouteFrom(
        source: IdentityKey,
        aliases: List<MutationKeyAliasRecord>,
    ): Set<IdentityKey> {
        val aliasesBySource =
            aliases.associateBy { alias ->
                IdentityKey(alias.sourceNamespace, alias.sourceCanonicalId)
            }
        val route = linkedSetOf<IdentityKey>()
        var cursor = source
        while (route.add(cursor)) {
            val alias = aliasesBySource[cursor] ?: return route
            if (alias.state != MutationAliasState.ACTIVE) return route
            cursor = IdentityKey(alias.targetNamespace, alias.targetCanonicalId)
        }
        throw IllegalArgumentException("an active alias route cannot contain a cycle")
    }

    private fun insertTombstoneRow(record: MutationKeyTombstoneRecord) {
        execute(
            """INSERT INTO store6_key_tombstone(
               namespace, canonical_id, created_by_client_id, created_by_sequence, state,
               created_at, activated_at, superseded_by_client_id, superseded_by_sequence,
               superseded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            10,
        ) {
            bindString(0, record.namespace)
            bindString(1, record.canonicalId)
            bindString(2, record.createdByClientId)
            bindLong(3, record.createdBySequence)
            bindString(4, record.state.name)
            bindLong(5, record.createdAt)
            bindLong(6, record.activatedAt)
            bindString(7, record.supersededByClientId)
            bindLong(8, record.supersededBySequence)
            bindLong(9, record.supersededAt)
        }.requireOneRow("tombstone insert")
    }

    private fun wouldCreateAliasCycle(record: MutationKeyAliasRecord): Boolean {
        val rows = aliases().associateBy { IdentityKey(it.sourceNamespace, it.sourceCanonicalId) }
        val source = IdentityKey(record.sourceNamespace, record.sourceCanonicalId)
        var cursor = IdentityKey(record.targetNamespace, record.targetCanonicalId)
        val visited = mutableSetOf<IdentityKey>()
        while (visited.add(cursor)) {
            if (cursor == source) return true
            val next = rows[cursor] ?: return false
            cursor = IdentityKey(next.targetNamespace, next.targetCanonicalId)
        }
        return true
    }

    private fun requireNoOtherTombstoneInState(
        record: MutationKeyTombstoneRecord,
        state: MutationTombstoneState,
        excluding: MutationKeyTombstoneRecord?,
    ) {
        if (state == MutationTombstoneState.SUPERSEDED) return
        require(
            tombstones().none { existing ->
                (excluding == null || !existing.sameKeyAs(excluding)) &&
                    existing.namespace == record.namespace &&
                    existing.canonicalId == record.canonicalId &&
                    existing.state == state
            },
        ) { "effective identity already has a $state tombstone generation" }
    }

    private fun MutationKeyTombstoneRecord.supersedingIntentIsConfirmed(): Boolean {
        val successorClientId = supersededByClientId ?: return false
        val successorSequence = supersededBySequence ?: return false
        val successorClient = client(successorClientId) ?: return false
        return successorSequence <= successorClient.serverConfirmedRetiredThroughSequence
    }

    private fun SqlPreparedStatement.bindAttempt(record: MutationAttemptRecord) {
        bindString(0, record.clientId)
        bindLong(1, record.clientSequence)
        bindLong(2, record.generation.toLong())
        bindString(3, record.effectiveNamespace)
        bindString(4, record.effectiveCanonicalId)
        bindLong(5, record.valueCodecVersion.toLong())
        bindString(6, record.basePresence.name)
        bindBytes(7, record.baseBlob?.copyOf())
        bindString(8, record.minePresence.name)
        bindBytes(9, record.mineBlob?.copyOf())
        bindBoolean(10, record.preconditionMetaPresent)
        bindLong(11, record.preconditionWrittenAt)
        bindString(12, record.preconditionEtag)
        bindLong(13, record.advertisedRetiredThroughSequence)
        bindString(14, record.generationIdempotencyKey)
        bindLong(15, record.preparedAt)
        bindBoolean(16, record.conflictMetaPresent)
        bindLong(17, record.conflictWrittenAt)
        bindString(18, record.conflictEtag)
        bindLong(19, record.conflictReceivedAt)
    }

    private fun requireActive() {
        check(active) { "mutation journal transaction is no longer active" }
    }

    private fun execute(
        sql: String,
        parameters: Int = 0,
        binders: SqlPreparedStatement.() -> Unit = {},
    ): Long = driver.execute(null, sql, parameters, binders).value

    private fun exists(
        sql: String,
        parameters: Int,
        binders: SqlPreparedStatement.() -> Unit,
    ): Boolean = queryOne(sql, parameters, binders) { true } == true

    private fun queryLong(sql: String): Long? = queryOne(sql) { cursor -> cursor.getLong(0) }

    private fun <T> queryOne(
        sql: String,
        parameters: Int = 0,
        binders: SqlPreparedStatement.() -> Unit = {},
        mapper: (SqlCursor) -> T,
    ): T? =
        driver.executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(if (cursor.next().value) mapper(cursor) else null) },
            parameters,
            binders,
        ).value

    private fun <T> queryList(
        sql: String,
        parameters: Int = 0,
        binders: SqlPreparedStatement.() -> Unit = {},
        mapper: (SqlCursor) -> T,
    ): List<T> =
        driver.executeQuery(
            null,
            sql,
            { cursor ->
                val rows = mutableListOf<T>()
                while (cursor.next().value) rows += mapper(cursor)
                QueryResult.Value(rows)
            },
            parameters,
            binders,
        ).value
}

private fun SqlCursor.toIntent(): MutationIntentRecord =
    MutationIntentRecord(
        rowId = requiredLong(0),
        recordVersion = requiredInt(1, "intent record version"),
        clientId = requiredString(2),
        clientSequence = requiredLong(3),
        mutationId = requiredString(4),
        namespace = requiredString(5),
        canonicalId = requiredString(6),
        mutatorId = requiredString(7),
        mutatorVersion = requiredInt(8, "mutator version"),
        argsBlob = requireNotNull(getBytes(9)).copyOf(),
        idempotencyRoot = requiredString(10),
        createdAt = requiredLong(11),
    )

private fun SqlCursor.toAttempt(): MutationAttemptRecord =
    MutationAttemptRecord(
        clientId = requiredString(0),
        clientSequence = requiredLong(1),
        generation = requiredInt(2, "attempt generation"),
        effectiveNamespace = requiredString(3),
        effectiveCanonicalId = requiredString(4),
        valueCodecVersion = requiredInt(5, "attempt value codec version"),
        basePresence = enumNamed(requiredString(6), MutationPresenceState.entries, "presence"),
        baseBlob = getBytes(7)?.copyOf(),
        minePresence = enumNamed(requiredString(8), MutationPresenceState.entries, "presence"),
        mineBlob = getBytes(9)?.copyOf(),
        preconditionMetaPresent = requireNotNull(getBoolean(10)),
        preconditionWrittenAt = getLong(11),
        preconditionEtag = getString(12),
        advertisedRetiredThroughSequence = requiredLong(13),
        generationIdempotencyKey = requiredString(14),
        preparedAt = requiredLong(15),
        conflictMetaPresent = getBoolean(16),
        conflictWrittenAt = getLong(17),
        conflictEtag = getString(18),
        conflictReceivedAt = getLong(19),
    )

private fun SqlCursor.toAck(): MutationAckRecord =
    MutationAckRecord(
        clientId = requiredString(0),
        clientSequence = requiredLong(1),
        generation = requiredInt(2, "acknowledgement generation"),
        authoritativePresence =
            enumNamed(requiredString(3), MutationPresenceState.entries, "presence"),
        authoritativeBlob = getBytes(4)?.copyOf(),
        valueCodecVersion = requiredInt(5, "acknowledgement value codec version"),
        etag = getString(6),
        canonicalTargetNamespace = getString(7),
        canonicalTargetId = getString(8),
        receivedAt = requiredLong(9),
    )

private fun SqlCursor.toFailure(): MutationFailureRecord =
    MutationFailureRecord(
        failureId = requiredLong(0),
        clientId = requiredString(1),
        clientSequence = requiredLong(2),
        generation = requiredInt(3, "failure generation"),
        kind = enumNamed(requiredString(4), MutationFailureKind.entries, "failure kind"),
        detail = requiredString(5),
        message = requiredString(6),
        occurredAt = requiredLong(7),
    )

private fun SqlCursor.toEffect(): MutationEffectRecord =
    MutationEffectRecord(
        clientId = requiredString(0),
        clientSequence = requiredLong(1),
        effectIndex = requiredInt(2, "effect index"),
        kind = enumNamed(requiredString(3), MutationEffectKind.entries, "effect kind"),
        namespace = requiredString(4),
        canonicalId = getString(5),
        createdAt = requiredLong(6),
        disposition =
            enumNamed(requiredString(7), MutationEffectDisposition.entries, "effect disposition"),
        completedAt = getLong(8),
    )

private fun SqlCursor.toAlias(): MutationKeyAliasRecord =
    MutationKeyAliasRecord(
        sourceNamespace = requiredString(0),
        sourceCanonicalId = requiredString(1),
        targetNamespace = requiredString(2),
        targetCanonicalId = requiredString(3),
        state = enumNamed(requiredString(4), MutationAliasState.entries, "alias state"),
        createdByClientId = requiredString(5),
        createdBySequence = requiredLong(6),
        createdAt = requiredLong(7),
        activatedAt = getLong(8),
    )

private fun SqlCursor.toTombstone(): MutationKeyTombstoneRecord =
    MutationKeyTombstoneRecord(
        namespace = requiredString(0),
        canonicalId = requiredString(1),
        createdByClientId = requiredString(2),
        createdBySequence = requiredLong(3),
        state = enumNamed(requiredString(4), MutationTombstoneState.entries, "tombstone state"),
        createdAt = requiredLong(5),
        activatedAt = getLong(6),
        supersededByClientId = getString(7),
        supersededBySequence = getLong(8),
        supersededAt = getLong(9),
    )

private fun SqlCursor.requiredLong(index: Int): Long =
    requireNotNull(getLong(index)) { "persisted journal column $index was unexpectedly null" }

private fun SqlCursor.requiredInt(
    index: Int,
    label: String,
): Int {
    val value = requiredLong(index)
    check(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "Persisted $label value $value is outside the Kotlin Int range"
    }
    return value.toInt()
}

private fun SqlCursor.requiredString(index: Int): String =
    requireNotNull(getString(index)) { "persisted journal column $index was unexpectedly null" }

private fun <E : Enum<E>> enumNamed(
    name: String,
    entries: List<E>,
    label: String,
): E =
    checkNotNull(entries.firstOrNull { entry -> entry.name == name }) {
        "Unknown persisted $label name '$name'"
    }

private fun Long.requireOneRow(operation: String) {
    check(this == 1L) { "$operation affected $this rows instead of one" }
}

private data class IdentityKey(
    val namespace: String,
    val canonicalId: String,
)

private data class AttemptKey(
    val clientId: String,
    val sequence: Long,
    val generation: Int,
)

private data class SuccessorKey(
    val clientId: String,
    val sequence: Long,
)

private data class ClientTransition(
    val previous: MutationClientRecord,
    val next: MutationClientRecord,
)

private data class ExecutionTransition(
    val previous: MutationExecutionRecord,
    val next: MutationExecutionRecord,
    val wasUniqueNamespaceOwner: Boolean,
)

private data class AliasTransition(
    val previous: MutationKeyAliasRecord,
    val next: MutationKeyAliasRecord,
)

private data class TombstoneTransition(
    val previous: MutationKeyTombstoneRecord,
    val next: MutationKeyTombstoneRecord,
) {
    fun isSameClientLowerSuccessor(): Boolean =
        next.state == MutationTombstoneState.SUPERSEDED &&
            next.supersededByClientId == previous.createdByClientId &&
            requireNotNull(next.supersededBySequence) < previous.createdBySequence
}

private fun MutationAttemptRecord.key(): AttemptKey =
    AttemptKey(clientId, clientSequence, generation)

private fun MutationExecutionRecord.ownsNamespaceAuthority(): Boolean =
    when (phase) {
        MutationExecutionPhase.INFLIGHT,
        MutationExecutionPhase.REFRESH_REQUIRED,
        MutationExecutionPhase.ACKED,
        MutationExecutionPhase.EFFECTS_PENDING,
        -> true

        MutationExecutionPhase.READY -> attempt > 0 || currentGeneration > 1
        MutationExecutionPhase.UNPREPARED,
        MutationExecutionPhase.PARKED,
        MutationExecutionPhase.RETIRED,
        -> false
    }

private fun requireExecutionTransition(
    previous: MutationExecutionRecord,
    next: MutationExecutionRecord,
    activeFailureKind: MutationFailureKind?,
) {
    require(previous.clientId == next.clientId && previous.clientSequence == next.clientSequence) {
        "execution identity is immutable"
    }
    require(next.attempt >= previous.attempt || next.currentGeneration > previous.currentGeneration) {
        "completed-attempt count cannot regress within a generation"
    }
    val generationAdvanced = next.currentGeneration > previous.currentGeneration
    if (generationAdvanced) {
        val initialPreparation =
            previous.phase == MutationExecutionPhase.UNPREPARED &&
                previous.currentGeneration == 0 &&
                next.phase == MutationExecutionPhase.READY &&
                next.currentGeneration == 1
        val refreshRetry =
            previous.phase == MutationExecutionPhase.REFRESH_REQUIRED &&
                next.phase == MutationExecutionPhase.READY &&
                next.currentGeneration == previous.currentGeneration + 1
        require(
            (initialPreparation || refreshRetry) && next.attempt == 0 && next.lastAttemptAt == null,
        ) { "generation may advance only for initial preparation or a g+1 refresh retry" }
    } else {
        require(next.currentGeneration == previous.currentGeneration) {
            "generation cannot change on this phase edge"
        }
    }
    if (
        previous.phase == MutationExecutionPhase.REFRESH_REQUIRED &&
        next.phase == MutationExecutionPhase.READY
    ) {
        require(generationAdvanced) {
            "REFRESH_REQUIRED must persist generation g+1 before returning to READY"
        }
    }
    val legalPhaseEdge =
        when (previous.phase) {
            MutationExecutionPhase.UNPREPARED ->
                next.phase == MutationExecutionPhase.READY || next.phase == MutationExecutionPhase.PARKED
            MutationExecutionPhase.READY ->
                next.phase == MutationExecutionPhase.INFLIGHT || next.phase == MutationExecutionPhase.PARKED
            MutationExecutionPhase.INFLIGHT ->
                next.phase == MutationExecutionPhase.READY ||
                    next.phase == MutationExecutionPhase.REFRESH_REQUIRED ||
                    next.phase == MutationExecutionPhase.ACKED ||
                    next.phase == MutationExecutionPhase.PARKED
            MutationExecutionPhase.REFRESH_REQUIRED ->
                next.phase == MutationExecutionPhase.READY ||
                    next.phase == MutationExecutionPhase.RETIRED ||
                    next.phase == MutationExecutionPhase.PARKED
            MutationExecutionPhase.ACKED -> next.phase == MutationExecutionPhase.EFFECTS_PENDING
            MutationExecutionPhase.EFFECTS_PENDING -> next.phase == MutationExecutionPhase.RETIRED
            MutationExecutionPhase.PARKED,
            MutationExecutionPhase.RETIRED,
            -> false
        }
    require(legalPhaseEdge) { "illegal execution phase edge ${previous.phase} -> ${next.phase}" }
    if (!generationAdvanced) {
        val preservesPreTransportAttemptFacts =
            previous.phase == MutationExecutionPhase.INFLIGHT &&
                next.phase == MutationExecutionPhase.PARKED &&
                next.attempt == previous.attempt &&
                next.lastAttemptAt == previous.lastAttemptAt &&
                (
                    activeFailureKind == MutationFailureKind.IDENTITY ||
                        activeFailureKind == MutationFailureKind.CODEC
                )
        when {
            preservesPreTransportAttemptFacts -> Unit

            previous.phase == MutationExecutionPhase.INFLIGHT -> {
                require(next.attempt == previous.attempt + 1) {
                    "a completed INFLIGHT transition advances the attempt count once"
                }
                require(next.lastAttemptAt != null) {
                    "a completed INFLIGHT transition records lastAttemptAt"
                }
            }
            else -> {
                require(next.attempt == previous.attempt) {
                    "attempt count may change only when an INFLIGHT invocation completes"
                }
                require(next.lastAttemptAt == previous.lastAttemptAt) {
                    "lastAttemptAt may change only when an INFLIGHT invocation completes"
                }
            }
        }
    }
}

private fun MutationExecutionRecord.sameValueAs(other: MutationExecutionRecord): Boolean =
    clientId == other.clientId && clientSequence == other.clientSequence && phase == other.phase &&
        currentGeneration == other.currentGeneration && attempt == other.attempt &&
        lastAttemptAt == other.lastAttemptAt && activeFailureId == other.activeFailureId &&
        retiredAt == other.retiredAt

private fun MutationAttemptRecord.samePreparationAs(other: MutationAttemptRecord): Boolean =
    clientId == other.clientId && clientSequence == other.clientSequence && generation == other.generation &&
        effectiveNamespace == other.effectiveNamespace &&
        effectiveCanonicalId == other.effectiveCanonicalId &&
        valueCodecVersion == other.valueCodecVersion && basePresence == other.basePresence &&
        baseBlob.contentEqualsNullable(other.baseBlob) && minePresence == other.minePresence &&
        mineBlob.contentEqualsNullable(other.mineBlob) &&
        preconditionMetaPresent == other.preconditionMetaPresent &&
        preconditionWrittenAt == other.preconditionWrittenAt &&
        preconditionEtag == other.preconditionEtag &&
        advertisedRetiredThroughSequence == other.advertisedRetiredThroughSequence &&
        generationIdempotencyKey == other.generationIdempotencyKey && preparedAt == other.preparedAt

private fun MutationAttemptRecord.sameValueAs(other: MutationAttemptRecord): Boolean =
    samePreparationAs(other) && conflictMetaPresent == other.conflictMetaPresent &&
        conflictWrittenAt == other.conflictWrittenAt && conflictEtag == other.conflictEtag &&
        conflictReceivedAt == other.conflictReceivedAt

private fun MutationAckRecord.sameValueAs(other: MutationAckRecord): Boolean =
    clientId == other.clientId && clientSequence == other.clientSequence && generation == other.generation &&
        authoritativePresence == other.authoritativePresence &&
        authoritativeBlob.contentEqualsNullable(other.authoritativeBlob) &&
        valueCodecVersion == other.valueCodecVersion && etag == other.etag &&
        canonicalTargetNamespace == other.canonicalTargetNamespace &&
        canonicalTargetId == other.canonicalTargetId

private fun MutationEffectRecord.sameIdentityAndTargetAs(other: MutationEffectRecord): Boolean =
    clientId == other.clientId && clientSequence == other.clientSequence &&
        effectIndex == other.effectIndex && kind == other.kind && namespace == other.namespace &&
        canonicalId == other.canonicalId && createdAt == other.createdAt

private fun MutationEffectRecord.sameValueAs(other: MutationEffectRecord): Boolean =
    sameIdentityAndTargetAs(other) && disposition == other.disposition && completedAt == other.completedAt

private fun MutationKeyAliasRecord.sameEdgeAs(other: MutationKeyAliasRecord): Boolean =
    sourceNamespace == other.sourceNamespace && sourceCanonicalId == other.sourceCanonicalId &&
        targetNamespace == other.targetNamespace && targetCanonicalId == other.targetCanonicalId &&
        createdByClientId == other.createdByClientId && createdBySequence == other.createdBySequence &&
        createdAt == other.createdAt

private fun MutationKeyAliasRecord.sameRouteAs(other: MutationKeyAliasRecord): Boolean =
    sourceNamespace == other.sourceNamespace && sourceCanonicalId == other.sourceCanonicalId &&
        targetNamespace == other.targetNamespace && targetCanonicalId == other.targetCanonicalId

private fun MutationKeyAliasRecord.sameValueAs(other: MutationKeyAliasRecord): Boolean =
    sameEdgeAs(other) && state == other.state && activatedAt == other.activatedAt

private fun MutationKeyTombstoneRecord.sameKeyAs(other: MutationKeyTombstoneRecord): Boolean =
    namespace == other.namespace && canonicalId == other.canonicalId &&
        createdByClientId == other.createdByClientId && createdBySequence == other.createdBySequence

private fun MutationKeyTombstoneRecord.sameGenerationAs(other: MutationKeyTombstoneRecord): Boolean =
    sameKeyAs(other) && createdAt == other.createdAt

private fun MutationKeyTombstoneRecord.sameValueAs(other: MutationKeyTombstoneRecord): Boolean =
    sameGenerationAs(other) && state == other.state && activatedAt == other.activatedAt &&
        supersededByClientId == other.supersededByClientId &&
        supersededBySequence == other.supersededBySequence && supersededAt == other.supersededAt

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    when {
        this == null -> other == null
        other == null -> false
        else -> contentEquals(other)
    }

private fun String.truncateUtf8(maxBytes: Int): String {
    if (encodeToByteArray().size <= maxBytes) return this
    val result = StringBuilder()
    var index = 0
    while (index < length) {
        val current = this[index]
        val width =
            if (
                current in '\uD800'..'\uDBFF' && index + 1 < length &&
                this[index + 1] in '\uDC00'..'\uDFFF'
            ) {
                2
            } else {
                1
            }
        val next = substring(index, index + width)
        if ((result.toString() + next).encodeToByteArray().size > maxBytes) break
        result.append(next)
        index += width
    }
    return result.toString()
}
