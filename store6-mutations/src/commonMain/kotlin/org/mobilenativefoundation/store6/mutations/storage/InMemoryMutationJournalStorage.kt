@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.mutations.MutationFailureKind
import org.mobilenativefoundation.store6.mutations.MutationPresenceState

/**
 * Process-local default [MutationJournalStorage].
 *
 * A transaction operates on a private snapshot under one [Mutex]. Normal return replaces the
 * committed snapshot; any thrown [Throwable] discards it. This implementation is intentionally
 * non-durable across process death, while returning the same instance from a contract-kit reopen
 * models reopening the same in-memory journal.
 */
@ExperimentalStoreApi
public class InMemoryMutationJournalStorage : MutationJournalStorage {
    private val mutex: Mutex = Mutex()
    private var committed: JournalState = JournalState()

    override suspend fun <R> transaction(block: (MutationJournalTransaction) -> R): R =
        mutex.withLock {
            val working = committed.mutableCopy()
            val transaction = InMemoryTransaction(working)
            try {
                val result = block(transaction)
                transaction.validateFinalState()
                transaction.close()
                committed = working
                result
            } catch (failure: Throwable) {
                transaction.close()
                throw failure
            }
        }
}

private class InMemoryTransaction(
    private val state: JournalState,
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
        return state.clients[clientId]?.freshCopy()
    }

    override fun intents(clientId: String): List<MutationIntentRecord> {
        requireActive()
        return state.intents.entries
            .asSequence()
            .filter { it.key.clientId == clientId }
            .sortedBy { it.key.sequence }
            .map { it.value.freshCopy() }
            .toList()
    }

    override fun executions(clientId: String): List<MutationExecutionRecord> {
        requireActive()
        return state.executions.entries
            .asSequence()
            .filter { it.key.clientId == clientId }
            .sortedBy { it.key.sequence }
            .map { it.value.freshCopy() }
            .toList()
    }

    override fun attempts(clientId: String): List<MutationAttemptRecord> {
        requireActive()
        return state.attempts.entries
            .asSequence()
            .filter { it.key.clientId == clientId }
            .sortedWith(compareBy({ it.key.sequence }, { it.key.generation }))
            .map { it.value.freshCopy() }
            .toList()
    }

    override fun acks(clientId: String): List<MutationAckRecord> {
        requireActive()
        return state.acks.entries
            .asSequence()
            .filter { it.key.clientId == clientId }
            .sortedWith(compareBy({ it.key.sequence }, { it.key.generation }))
            .map { it.value.freshCopy() }
            .toList()
    }

    override fun failures(clientId: String): List<MutationFailureRecord> {
        requireActive()
        return state.failures.values
            .asSequence()
            .filter { it.clientId == clientId }
            .sortedBy { it.failureId }
            .map { it.freshCopy() }
            .toList()
    }

    override fun effects(clientId: String): List<MutationEffectRecord> {
        requireActive()
        return state.effects.entries
            .asSequence()
            .filter { it.key.clientId == clientId }
            .sortedWith(compareBy({ it.key.sequence }, { it.key.index }))
            .map { it.value.freshCopy() }
            .toList()
    }

    override fun aliases(): List<MutationKeyAliasRecord> {
        requireActive()
        return state.aliases.entries
            .sortedWith(compareBy({ it.key.namespace }, { it.key.canonicalId }))
            .map { it.value.freshCopy() }
    }

    override fun tombstones(): List<MutationKeyTombstoneRecord> {
        requireActive()
        return state.tombstones.entries
            .sortedWith(
                compareBy(
                    { it.key.namespace },
                    { it.key.canonicalId },
                    { it.key.createdByClientId },
                    { it.key.createdBySequence },
                ),
            )
            .map { it.value.freshCopy() }
    }

    override fun insertClient(record: MutationClientRecord) {
        requireActive()
        require(record.clientId !in state.clients) { "client already exists: ${record.clientId}" }
        require(
            record.recordVersion == 1 &&
                record.lastAllocatedSequence == 0L &&
                record.retiredThroughSequence == 0L &&
                record.serverConfirmedRetiredThroughSequence == 0L,
        ) { "a new client must start at version 1 with zero allocation and retirement prefixes" }
        state.clients[record.clientId] = record.freshCopy()
    }

    override fun advanceClient(record: MutationClientRecord) {
        requireActive()
        val previous = requireNotNull(state.clients[record.clientId]) {
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
        clientTransitions += ClientTransition(previous, record)
        state.clients[record.clientId] = record.freshCopy()
    }

    override fun confirmRetiredThrough(
        clientId: String,
        requestedThroughSequence: Long,
        serverConfirmedThroughSequence: Long,
    ): MutationClientRecord {
        requireActive()
        val previous = requireNotNull(state.clients[clientId]) { "client does not exist: $clientId" }
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
        val advanced =
            MutationClientRecord(
                recordVersion = previous.recordVersion,
                clientId = previous.clientId,
                lastAllocatedSequence = previous.lastAllocatedSequence,
                retiredThroughSequence = previous.retiredThroughSequence,
                serverConfirmedRetiredThroughSequence = serverConfirmedThroughSequence,
                createdAt = previous.createdAt,
            )
        state.clients[clientId] = advanced
        return advanced.freshCopy()
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
        val client = requireNotNull(state.clients[clientId]) { "client does not exist: $clientId" }
        require(clientSequence > client.retiredThroughSequence) {
            "intent sequence must be newer than the retired client prefix"
        }
        require(clientSequence <= client.lastAllocatedSequence) {
            "intent sequence exceeds the allocated client sequence"
        }
        val key = IntentKey(clientId, clientSequence)
        require(key !in state.intents) { "intent sequence already exists: $clientSequence" }
        require(state.intents.values.none { it.clientId == clientId && it.mutationId == mutationId }) {
            "mutationId already exists for client: $mutationId"
        }
        require(state.intents.values.none { it.idempotencyRoot == idempotencyRoot }) {
            "idempotencyRoot already exists: $idempotencyRoot"
        }
        val record =
            MutationIntentRecord(
                rowId = state.nextIntentRowId,
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
        state.nextIntentRowId += 1L
        state.intents[key] = record
        return record.freshCopy()
    }

    override fun insertExecution(record: MutationExecutionRecord) {
        requireActive()
        val key = IntentKey(record.clientId, record.clientSequence)
        require(key in state.intents) { "execution requires an intent" }
        require(key !in state.executions) { "execution already exists" }
        require(record.phase == MutationExecutionPhase.UNPREPARED) {
            "new execution must start UNPREPARED"
        }
        state.executions[key] = record.freshCopy()
    }

    override fun advanceExecution(record: MutationExecutionRecord) {
        requireActive()
        val key = IntentKey(record.clientId, record.clientSequence)
        val previous = requireNotNull(state.executions[key]) { "execution does not exist" }
        if (previous.sameValueAs(record)) return

        require(record.currentGeneration >= previous.currentGeneration) {
            "currentGeneration cannot regress"
        }
        require(record.attempt >= 0) { "attempt cannot be negative" }
        val activeFailureKind =
            if (record.phase == MutationExecutionPhase.PARKED) {
                val activeFailureId = requireNotNull(record.activeFailureId)
                val failure = requireNotNull(state.failures[activeFailureId]) {
                    "PARKED requires its active failure row"
                }
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
            require(AttemptKey(record.clientId, record.clientSequence, record.currentGeneration) in state.attempts) {
                "execution generation requires an immutable attempt row"
            }
        }
        val generationContinuation =
            previous.phase == MutationExecutionPhase.REFRESH_REQUIRED &&
                record.phase == MutationExecutionPhase.READY &&
                record.currentGeneration == previous.currentGeneration + 1
        if (generationContinuation) {
            val previousAttempt =
                requireNotNull(
                    state.attempts[
                        AttemptKey(
                            record.clientId,
                            record.clientSequence,
                            previous.currentGeneration,
                        )
                    ],
                ) { "generation continuation requires the previous immutable attempt" }
            val nextAttempt =
                requireNotNull(
                    state.attempts[
                        AttemptKey(
                            record.clientId,
                            record.clientSequence,
                            record.currentGeneration,
                        )
                    ],
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
            require(AttemptKey(record.clientId, record.clientSequence, record.currentGeneration) in state.acks) {
                "ACKED requires its matching acknowledgement"
            }
        }
        if (record.phase == MutationExecutionPhase.RETIRED) {
            require(
                state.effects.values.none {
                    it.clientId == record.clientId &&
                        it.clientSequence == record.clientSequence &&
                        it.disposition == MutationEffectDisposition.PENDING
                },
            ) { "an execution cannot retire with a pending effect" }
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
        executionTransitions +=
            ExecutionTransition(
                previous = previous,
                next = record,
                wasUniqueNamespaceOwner = wasUniqueNamespaceOwner,
            )
        state.executions[key] = record.freshCopy()
    }

    override fun insertAttempt(record: MutationAttemptRecord) {
        requireActive()
        val intentKey = IntentKey(record.clientId, record.clientSequence)
        require(intentKey in state.intents) { "attempt requires an intent" }
        val key = AttemptKey(record.clientId, record.clientSequence, record.generation)
        require(key !in state.attempts) { "attempt generation already exists" }
        require(
            state.attempts.values.none {
                it.generationIdempotencyKey == record.generationIdempotencyKey
            },
        ) { "generationIdempotencyKey already exists" }
        require(record.conflictMetaPresent == null) { "a new attempt cannot contain a conflict receipt" }
        state.attempts[key] = record.freshCopy()
    }

    override fun recordConflictReceipt(record: MutationAttemptRecord) {
        requireActive()
        val key = AttemptKey(record.clientId, record.clientSequence, record.generation)
        val previous = requireNotNull(state.attempts[key]) { "attempt does not exist" }
        require(previous.samePreparationAs(record)) { "attempt preparation fields are immutable" }
        require(record.conflictMetaPresent != null && record.conflictReceivedAt != null) {
            "conflict receipt is incomplete"
        }
        if (previous.sameValueAs(record)) return
        require(previous.conflictMetaPresent == null) { "conflict receipt is write-once" }
        state.attempts[key] = record.freshCopy()
    }

    override fun insertAck(record: MutationAckRecord) {
        requireActive()
        val attemptKey = AttemptKey(record.clientId, record.clientSequence, record.generation)
        val attempt = requireNotNull(state.attempts[attemptKey]) {
            "acknowledgement requires its attempt generation"
        }
        if (record.canonicalTargetNamespace != null) {
            require(record.canonicalTargetNamespace == attempt.effectiveNamespace) {
                "canonical acknowledgement target cannot cross namespaces"
            }
        }
        val previous = state.acks[attemptKey]
        if (previous != null) {
            require(previous.sameValueAs(record)) { "acknowledgement is write-once" }
            return
        }
        state.acks[attemptKey] = record.freshCopy()
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
        require(IntentKey(clientId, clientSequence) in state.intents) { "failure requires an intent" }
        val record =
            MutationFailureRecord(
                failureId = state.nextFailureId,
                clientId = clientId,
                clientSequence = clientSequence,
                generation = generation,
                kind = kind,
                detail = detail.truncateUtf8(FAILURE_DETAIL_MAX_UTF8_BYTES),
                message = message.truncateUtf8(FAILURE_MESSAGE_MAX_UTF8_BYTES),
                occurredAt = occurredAt,
            )
        state.nextFailureId += 1L
        state.failures[record.failureId] = record
        return record.freshCopy()
    }

    override fun insertEffect(record: MutationEffectRecord) {
        requireActive()
        require(IntentKey(record.clientId, record.clientSequence) in state.intents) {
            "effect requires an intent"
        }
        require(record.disposition == MutationEffectDisposition.PENDING) {
            "new effect must start PENDING"
        }
        val key = EffectKey(record.clientId, record.clientSequence, record.effectIndex)
        require(key !in state.effects) { "effect identity already exists" }
        state.effects[key] = record.freshCopy()
    }

    override fun advanceEffect(record: MutationEffectRecord) {
        requireActive()
        val key = EffectKey(record.clientId, record.clientSequence, record.effectIndex)
        val previous = requireNotNull(state.effects[key]) { "effect does not exist" }
        if (previous.sameValueAs(record)) return
        require(previous.sameIdentityAndTargetAs(record)) { "effect identity and target are immutable" }
        require(previous.disposition == MutationEffectDisposition.PENDING) {
            "terminal effect disposition cannot change"
        }
        require(record.disposition != MutationEffectDisposition.PENDING) {
            "effect disposition cannot regress to PENDING"
        }
        state.effects[key] = record.freshCopy()
    }

    override fun insertAlias(record: MutationKeyAliasRecord) {
        requireActive()
        require(record.state == MutationAliasState.PENDING) { "new alias must start PENDING" }
        val key = IdentityKey(record.sourceNamespace, record.sourceCanonicalId)
        val previous = state.aliases[key]
        if (previous != null) {
            require(previous.sameRouteAs(record)) { "alias source cannot be retargeted" }
            return
        }
        require(!wouldCreateAliasCycle(record)) { "alias edge would create a cycle" }
        state.aliases[key] = record.freshCopy()
    }

    override fun advanceAlias(record: MutationKeyAliasRecord) {
        requireActive()
        val key = IdentityKey(record.sourceNamespace, record.sourceCanonicalId)
        val previous = requireNotNull(state.aliases[key]) { "alias does not exist" }
        if (previous.sameValueAs(record)) return
        require(previous.sameEdgeAs(record)) { "alias edge and creator are immutable" }
        require(
            previous.state == MutationAliasState.PENDING && record.state == MutationAliasState.ACTIVE,
        ) { "alias state can only advance PENDING to ACTIVE" }
        aliasTransitions += AliasTransition(previous, record)
        state.aliases[key] = record.freshCopy()
    }

    override fun insertTombstone(record: MutationKeyTombstoneRecord) {
        requireActive()
        require(record.state == MutationTombstoneState.PENDING) {
            "new tombstone must start PENDING"
        }
        val key = record.key()
        val previous = state.tombstones[key]
        if (previous != null) {
            require(previous.sameValueAs(record)) { "tombstone generation is immutable" }
            return
        }
        requireNoOtherTombstoneInState(record, MutationTombstoneState.PENDING, excluding = null)
        state.tombstones[key] = record.freshCopy()
    }

    override fun advanceTombstone(record: MutationKeyTombstoneRecord) {
        requireActive()
        val key = record.key()
        val previous = requireNotNull(state.tombstones[key]) { "tombstone generation does not exist" }
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
        requireNoOtherTombstoneInState(record, record.state, excluding = key)
        tombstoneTransitions += TombstoneTransition(previous, record)
        state.tombstones[key] = record.freshCopy()
    }

    override fun prune(
        clientId: String,
        serverConfirmedRetiredThroughSequence: Long,
    ) {
        requireActive()
        val client = requireNotNull(state.clients[clientId]) { "client does not exist: $clientId" }
        require(serverConfirmedRetiredThroughSequence >= 0L) { "prune prefix must be non-negative" }
        require(
            serverConfirmedRetiredThroughSequence <=
                client.serverConfirmedRetiredThroughSequence,
        ) { "prune prefix exceeds the persisted server-confirmed prefix" }

        state.intents.keys.removeAll {
            it.clientId == clientId && it.sequence <= serverConfirmedRetiredThroughSequence
        }
        state.executions.keys.removeAll {
            it.clientId == clientId && it.sequence <= serverConfirmedRetiredThroughSequence
        }
        state.attempts.keys.removeAll {
            it.clientId == clientId && it.sequence <= serverConfirmedRetiredThroughSequence
        }
        state.acks.keys.removeAll {
            it.clientId == clientId && it.sequence <= serverConfirmedRetiredThroughSequence
        }
        state.failures.entries.removeAll {
            it.value.clientId == clientId &&
                it.value.clientSequence <= serverConfirmedRetiredThroughSequence
        }
        state.effects.keys.removeAll {
            it.clientId == clientId && it.sequence <= serverConfirmedRetiredThroughSequence
        }
        state.tombstones.entries.removeAll {
            it.value.createdByClientId == clientId &&
                it.value.createdBySequence <= serverConfirmedRetiredThroughSequence &&
                it.value.state == MutationTombstoneState.SUPERSEDED &&
                it.value.supersedingIntentIsConfirmed()
        }
    }

    private fun requireActive() {
        check(active) { "mutation journal transaction is no longer active" }
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

        val successorExecution =
            state.executions[IntentKey(successor.clientId, successor.sequence)]
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
        val attempt = requireNotNull(state.attempts[attemptKey]) {
            "a lower causal successor requires its exact attempt"
        }
        val acknowledgement = requireNotNull(state.acks[attemptKey]) {
            "a lower causal successor requires its exact acknowledgement"
        }
        require(
            acknowledgement.authoritativePresence == MutationPresenceState.PRESENT &&
                acknowledgement.canonicalTargetNamespace != null &&
                acknowledgement.canonicalTargetId != null,
        ) { "a lower causal successor requires a PRESENT canonical acknowledgement" }
        require(
            state.effects.values.none { effect ->
                effect.clientId == successor.clientId &&
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
        val finalAlias =
            state.aliases[
                IdentityKey(
                    namespace = aliasTransition.next.sourceNamespace,
                    canonicalId = aliasTransition.next.sourceCanonicalId,
                )
            ]
        require(finalAlias != null && finalAlias.sameValueAs(aliasTransition.next)) {
            "a lower causal successor must commit its activated alias"
        }

        val route =
            activeRouteFrom(
                IdentityKey(attempt.effectiveNamespace, attempt.effectiveCanonicalId),
            )
        require(
            predecessors.all { transition ->
                IdentityKey(transition.previous.namespace, transition.previous.canonicalId) in route
            },
        ) { "a lower causal successor route must contain every predecessor" }

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
                    state.tombstones[transition.next.key()]?.sameValueAs(transition.next) == true
            },
        ) { "every collapsed ACTIVE tombstone must name the exact causal successor" }
        require(
            state.tombstones.values.none { tombstone ->
                tombstone.state == MutationTombstoneState.ACTIVE &&
                    IdentityKey(tombstone.namespace, tombstone.canonicalId) in route
            },
        ) { "a lower causal successor must collapse every ACTIVE tombstone in its route" }

        requireTruthfulPrefixAdvance(successor.clientId)
        require(
            state.executions.values.none { execution ->
                execution.clientId == successor.clientId &&
                    execution.ownsNamespaceAuthority() &&
                    namespaceFor(execution) == attempt.effectiveNamespace
            },
        ) { "a lower causal successor must release namespace authority in the same commit" }
    }

    private fun requireTruthfulPrefixAdvance(clientId: String) {
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
        val finalClient = requireNotNull(state.clients[clientId])
        var truthfulPrefix = startingPrefix
        while (truthfulPrefix < finalClient.lastAllocatedSequence) {
            val next = state.executions[IntentKey(clientId, truthfulPrefix + 1L)] ?: break
            if (next.phase != MutationExecutionPhase.RETIRED) break
            truthfulPrefix += 1L
        }
        require(
            finalClient.retiredThroughSequence > startingPrefix &&
                finalClient.retiredThroughSequence == truthfulPrefix,
        ) { "a lower causal successor requires an exact truthful retirement-prefix advance" }
    }

    private fun requireNamespaceAuthorityAvailable(candidate: MutationExecutionRecord) {
        val namespace = namespaceFor(candidate)
        require(
            state.executions.values.none { execution ->
                execution.clientId == candidate.clientId &&
                    execution.clientSequence != candidate.clientSequence &&
                    execution.ownsNamespaceAuthority() &&
                    namespaceFor(execution) == namespace
            },
        ) { "namespace authority already has an owner" }
    }

    private fun isUniqueNamespaceOwner(candidate: MutationExecutionRecord): Boolean {
        if (!candidate.ownsNamespaceAuthority()) return false
        val namespace = namespaceFor(candidate)
        val owners =
            state.executions.values.filter { execution ->
                execution.clientId == candidate.clientId &&
                    execution.ownsNamespaceAuthority() &&
                    namespaceFor(execution) == namespace
            }
        return owners.size == 1 &&
            owners.single().clientSequence == candidate.clientSequence
    }

    private fun namespaceFor(execution: MutationExecutionRecord): String =
        requireNotNull(
            state.attempts[
                AttemptKey(
                    execution.clientId,
                    execution.clientSequence,
                    execution.currentGeneration,
                )
            ],
        ) { "namespace authority requires the exact current attempt" }.effectiveNamespace

    private fun activeRouteFrom(source: IdentityKey): Set<IdentityKey> {
        val route = linkedSetOf<IdentityKey>()
        var cursor = source
        while (route.add(cursor)) {
            val alias = state.aliases[cursor] ?: return route
            if (alias.state != MutationAliasState.ACTIVE) return route
            cursor = IdentityKey(alias.targetNamespace, alias.targetCanonicalId)
        }
        throw IllegalArgumentException("an active alias route cannot contain a cycle")
    }

    private fun wouldCreateAliasCycle(record: MutationKeyAliasRecord): Boolean {
        val source = IdentityKey(record.sourceNamespace, record.sourceCanonicalId)
        var cursor = IdentityKey(record.targetNamespace, record.targetCanonicalId)
        val visited = mutableSetOf<IdentityKey>()
        while (visited.add(cursor)) {
            if (cursor == source) return true
            val next = state.aliases[cursor] ?: return false
            cursor = IdentityKey(next.targetNamespace, next.targetCanonicalId)
        }
        return true
    }

    private fun requireNoOtherTombstoneInState(
        record: MutationKeyTombstoneRecord,
        tombstoneState: MutationTombstoneState,
        excluding: TombstoneKey?,
    ) {
        if (tombstoneState == MutationTombstoneState.SUPERSEDED) return
        require(
            state.tombstones.none { (key, existing) ->
                key != excluding &&
                    existing.namespace == record.namespace &&
                    existing.canonicalId == record.canonicalId &&
                    existing.state == tombstoneState
            },
        ) { "effective identity already has a $tombstoneState tombstone generation" }
    }

    private fun MutationKeyTombstoneRecord.supersedingIntentIsConfirmed(): Boolean {
        val successorClientId = supersededByClientId ?: return false
        val successorSequence = supersededBySequence ?: return false
        val successorClient = this@InMemoryTransaction.state.clients[successorClientId] ?: return false
        return successorSequence <= successorClient.serverConfirmedRetiredThroughSequence
    }
}

private class JournalState(
    val clients: MutableMap<String, MutationClientRecord> = mutableMapOf(),
    val intents: MutableMap<IntentKey, MutationIntentRecord> = mutableMapOf(),
    val executions: MutableMap<IntentKey, MutationExecutionRecord> = mutableMapOf(),
    val attempts: MutableMap<AttemptKey, MutationAttemptRecord> = mutableMapOf(),
    val acks: MutableMap<AttemptKey, MutationAckRecord> = mutableMapOf(),
    val failures: MutableMap<Long, MutationFailureRecord> = mutableMapOf(),
    val effects: MutableMap<EffectKey, MutationEffectRecord> = mutableMapOf(),
    val aliases: MutableMap<IdentityKey, MutationKeyAliasRecord> = mutableMapOf(),
    val tombstones: MutableMap<TombstoneKey, MutationKeyTombstoneRecord> = mutableMapOf(),
    var nextIntentRowId: Long = 1L,
    var nextFailureId: Long = 1L,
) {
    fun mutableCopy(): JournalState =
        JournalState(
            clients = clients.toMutableMap(),
            intents = intents.toMutableMap(),
            executions = executions.toMutableMap(),
            attempts = attempts.toMutableMap(),
            acks = acks.toMutableMap(),
            failures = failures.toMutableMap(),
            effects = effects.toMutableMap(),
            aliases = aliases.toMutableMap(),
            tombstones = tombstones.toMutableMap(),
            nextIntentRowId = nextIntentRowId,
            nextFailureId = nextFailureId,
        )
}

private data class IntentKey(
    val clientId: String,
    val sequence: Long,
)

private data class AttemptKey(
    val clientId: String,
    val sequence: Long,
    val generation: Int,
)

private data class EffectKey(
    val clientId: String,
    val sequence: Long,
    val index: Int,
)

private data class IdentityKey(
    val namespace: String,
    val canonicalId: String,
)

private data class TombstoneKey(
    val namespace: String,
    val canonicalId: String,
    val createdByClientId: String,
    val createdBySequence: Long,
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
                next.phase == MutationExecutionPhase.READY ||
                    next.phase == MutationExecutionPhase.PARKED

            MutationExecutionPhase.READY ->
                next.phase == MutationExecutionPhase.INFLIGHT ||
                    next.phase == MutationExecutionPhase.PARKED

            MutationExecutionPhase.INFLIGHT ->
                next.phase == MutationExecutionPhase.READY ||
                    next.phase == MutationExecutionPhase.REFRESH_REQUIRED ||
                    next.phase == MutationExecutionPhase.ACKED ||
                    next.phase == MutationExecutionPhase.PARKED

            MutationExecutionPhase.REFRESH_REQUIRED ->
                next.phase == MutationExecutionPhase.READY ||
                    next.phase == MutationExecutionPhase.RETIRED ||
                    next.phase == MutationExecutionPhase.PARKED

            MutationExecutionPhase.ACKED ->
                next.phase == MutationExecutionPhase.EFFECTS_PENDING

            MutationExecutionPhase.EFFECTS_PENDING ->
                next.phase == MutationExecutionPhase.RETIRED

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

private fun MutationClientRecord.freshCopy(): MutationClientRecord =
    MutationClientRecord(
        recordVersion = recordVersion,
        clientId = clientId,
        lastAllocatedSequence = lastAllocatedSequence,
        retiredThroughSequence = retiredThroughSequence,
        serverConfirmedRetiredThroughSequence = serverConfirmedRetiredThroughSequence,
        createdAt = createdAt,
    )

private fun MutationIntentRecord.freshCopy(): MutationIntentRecord =
    MutationIntentRecord(
        rowId = rowId,
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

private fun MutationExecutionRecord.freshCopy(): MutationExecutionRecord =
    MutationExecutionRecord(
        clientId = clientId,
        clientSequence = clientSequence,
        phase = phase,
        currentGeneration = currentGeneration,
        attempt = attempt,
        lastAttemptAt = lastAttemptAt,
        activeFailureId = activeFailureId,
        retiredAt = retiredAt,
    )

private fun MutationAttemptRecord.freshCopy(): MutationAttemptRecord =
    MutationAttemptRecord(
        clientId = clientId,
        clientSequence = clientSequence,
        generation = generation,
        effectiveNamespace = effectiveNamespace,
        effectiveCanonicalId = effectiveCanonicalId,
        valueCodecVersion = valueCodecVersion,
        basePresence = basePresence,
        baseBlob = baseBlob,
        minePresence = minePresence,
        mineBlob = mineBlob,
        preconditionMetaPresent = preconditionMetaPresent,
        preconditionWrittenAt = preconditionWrittenAt,
        preconditionEtag = preconditionEtag,
        advertisedRetiredThroughSequence = advertisedRetiredThroughSequence,
        generationIdempotencyKey = generationIdempotencyKey,
        preparedAt = preparedAt,
        conflictMetaPresent = conflictMetaPresent,
        conflictWrittenAt = conflictWrittenAt,
        conflictEtag = conflictEtag,
        conflictReceivedAt = conflictReceivedAt,
    )

private fun MutationAckRecord.freshCopy(): MutationAckRecord =
    MutationAckRecord(
        clientId = clientId,
        clientSequence = clientSequence,
        generation = generation,
        authoritativePresence = authoritativePresence,
        authoritativeBlob = authoritativeBlob,
        valueCodecVersion = valueCodecVersion,
        etag = etag,
        canonicalTargetNamespace = canonicalTargetNamespace,
        canonicalTargetId = canonicalTargetId,
        receivedAt = receivedAt,
    )

private fun MutationFailureRecord.freshCopy(): MutationFailureRecord =
    MutationFailureRecord(
        failureId = failureId,
        clientId = clientId,
        clientSequence = clientSequence,
        generation = generation,
        kind = kind,
        detail = detail,
        message = message,
        occurredAt = occurredAt,
    )

private fun MutationEffectRecord.freshCopy(): MutationEffectRecord =
    MutationEffectRecord(
        clientId = clientId,
        clientSequence = clientSequence,
        effectIndex = effectIndex,
        kind = kind,
        namespace = namespace,
        canonicalId = canonicalId,
        createdAt = createdAt,
        disposition = disposition,
        completedAt = completedAt,
    )

private fun MutationKeyAliasRecord.freshCopy(): MutationKeyAliasRecord =
    MutationKeyAliasRecord(
        sourceNamespace = sourceNamespace,
        sourceCanonicalId = sourceCanonicalId,
        targetNamespace = targetNamespace,
        targetCanonicalId = targetCanonicalId,
        state = state,
        createdByClientId = createdByClientId,
        createdBySequence = createdBySequence,
        createdAt = createdAt,
        activatedAt = activatedAt,
    )

private fun MutationKeyTombstoneRecord.freshCopy(): MutationKeyTombstoneRecord =
    MutationKeyTombstoneRecord(
        namespace = namespace,
        canonicalId = canonicalId,
        createdByClientId = createdByClientId,
        createdBySequence = createdBySequence,
        state = state,
        createdAt = createdAt,
        activatedAt = activatedAt,
        supersededByClientId = supersededByClientId,
        supersededBySequence = supersededBySequence,
        supersededAt = supersededAt,
    )

private fun MutationExecutionRecord.sameValueAs(other: MutationExecutionRecord): Boolean =
    clientId == other.clientId &&
        clientSequence == other.clientSequence &&
        phase == other.phase &&
        currentGeneration == other.currentGeneration &&
        attempt == other.attempt &&
        lastAttemptAt == other.lastAttemptAt &&
        activeFailureId == other.activeFailureId &&
        retiredAt == other.retiredAt

private fun MutationAttemptRecord.samePreparationAs(other: MutationAttemptRecord): Boolean =
    clientId == other.clientId &&
        clientSequence == other.clientSequence &&
        generation == other.generation &&
        effectiveNamespace == other.effectiveNamespace &&
        effectiveCanonicalId == other.effectiveCanonicalId &&
        valueCodecVersion == other.valueCodecVersion &&
        basePresence == other.basePresence &&
        baseBlob.contentEqualsNullable(other.baseBlob) &&
        minePresence == other.minePresence &&
        mineBlob.contentEqualsNullable(other.mineBlob) &&
        preconditionMetaPresent == other.preconditionMetaPresent &&
        preconditionWrittenAt == other.preconditionWrittenAt &&
        preconditionEtag == other.preconditionEtag &&
        advertisedRetiredThroughSequence == other.advertisedRetiredThroughSequence &&
        generationIdempotencyKey == other.generationIdempotencyKey &&
        preparedAt == other.preparedAt

private fun MutationAttemptRecord.sameValueAs(other: MutationAttemptRecord): Boolean =
    samePreparationAs(other) &&
        conflictMetaPresent == other.conflictMetaPresent &&
        conflictWrittenAt == other.conflictWrittenAt &&
        conflictEtag == other.conflictEtag &&
        conflictReceivedAt == other.conflictReceivedAt

private fun MutationAckRecord.sameValueAs(other: MutationAckRecord): Boolean =
    clientId == other.clientId &&
        clientSequence == other.clientSequence &&
        generation == other.generation &&
        authoritativePresence == other.authoritativePresence &&
        authoritativeBlob.contentEqualsNullable(other.authoritativeBlob) &&
        valueCodecVersion == other.valueCodecVersion &&
        etag == other.etag &&
        canonicalTargetNamespace == other.canonicalTargetNamespace &&
        canonicalTargetId == other.canonicalTargetId

private fun MutationEffectRecord.sameIdentityAndTargetAs(other: MutationEffectRecord): Boolean =
    clientId == other.clientId &&
        clientSequence == other.clientSequence &&
        effectIndex == other.effectIndex &&
        kind == other.kind &&
        namespace == other.namespace &&
        canonicalId == other.canonicalId &&
        createdAt == other.createdAt

private fun MutationEffectRecord.sameValueAs(other: MutationEffectRecord): Boolean =
    sameIdentityAndTargetAs(other) &&
        disposition == other.disposition &&
        completedAt == other.completedAt

private fun MutationKeyAliasRecord.sameEdgeAs(other: MutationKeyAliasRecord): Boolean =
    sourceNamespace == other.sourceNamespace &&
        sourceCanonicalId == other.sourceCanonicalId &&
        targetNamespace == other.targetNamespace &&
        targetCanonicalId == other.targetCanonicalId &&
        createdByClientId == other.createdByClientId &&
        createdBySequence == other.createdBySequence &&
        createdAt == other.createdAt

private fun MutationKeyAliasRecord.sameRouteAs(other: MutationKeyAliasRecord): Boolean =
    sourceNamespace == other.sourceNamespace &&
        sourceCanonicalId == other.sourceCanonicalId &&
        targetNamespace == other.targetNamespace &&
        targetCanonicalId == other.targetCanonicalId

private fun MutationKeyAliasRecord.sameValueAs(other: MutationKeyAliasRecord): Boolean =
    sameEdgeAs(other) && state == other.state && activatedAt == other.activatedAt

private fun MutationKeyTombstoneRecord.key(): TombstoneKey =
    TombstoneKey(namespace, canonicalId, createdByClientId, createdBySequence)

private fun MutationKeyTombstoneRecord.sameGenerationAs(
    other: MutationKeyTombstoneRecord,
): Boolean =
    namespace == other.namespace &&
        canonicalId == other.canonicalId &&
        createdByClientId == other.createdByClientId &&
        createdBySequence == other.createdBySequence &&
        createdAt == other.createdAt

private fun MutationKeyTombstoneRecord.sameValueAs(other: MutationKeyTombstoneRecord): Boolean =
    sameGenerationAs(other) &&
        state == other.state &&
        activatedAt == other.activatedAt &&
        supersededByClientId == other.supersededByClientId &&
        supersededBySequence == other.supersededBySequence &&
        supersededAt == other.supersededAt

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
                current in '\uD800'..'\uDBFF' &&
                index + 1 < length &&
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
