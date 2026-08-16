@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.forClasses
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAckRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectDisposition
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationFailureRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationIntentRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalTransaction
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyAliasRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyTombstoneRecord
import kotlin.test.Test

class MutationJournalLincheckTest {
    private val storage: InMemoryMutationJournalStorage = seededLincheckStorage()
    private val records = LincheckRecordFactory()

    @Operation(runOnce = true, cancellableOnSuspension = false)
    suspend fun appendA(): JournalAppendResult = append(LINCHECK_SLOT_A)

    @Operation(runOnce = true, cancellableOnSuspension = false)
    suspend fun appendB(): JournalAppendResult = append(LINCHECK_SLOT_B)

    @Operation(runOnce = true, cancellableOnSuspension = false)
    suspend fun retireA(): JournalRetireResult = retire(LINCHECK_SLOT_A)

    @Operation(runOnce = true, cancellableOnSuspension = false)
    suspend fun retireB(): JournalRetireResult = retire(LINCHECK_SLOT_B)

    @Operation(runOnce = true, cancellableOnSuspension = false)
    suspend fun confirmTwo(): Long =
        storage.transaction { transaction ->
            val client = requireNotNull(transaction.client(LINCHECK_CLIENT_ID))
            val target = minOf(2L, client.retiredThroughSequence)
            if (target > client.serverConfirmedRetiredThroughSequence) {
                transaction.confirmRetiredThrough(
                    clientId = LINCHECK_CLIENT_ID,
                    requestedThroughSequence = target,
                    serverConfirmedThroughSequence = target,
                ).serverConfirmedRetiredThroughSequence
            } else {
                client.serverConfirmedRetiredThroughSequence
            }
        }

    @Operation(cancellableOnSuspension = false)
    suspend fun prune(): JournalView {
        val rows =
            storage.transaction { transaction ->
                val confirmed =
                    requireNotNull(transaction.client(LINCHECK_CLIENT_ID))
                        .serverConfirmedRetiredThroughSequence
                transaction.prune(LINCHECK_CLIENT_ID, confirmed)
                transaction.toJournalRows()
            }
        return JournalViewNormalizer.normalize(rows)
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun hydrate(): JournalView {
        val rows = storage.transaction(MutationJournalTransaction::toJournalRows)
        return JournalViewNormalizer.normalize(rows)
    }

    @Test
    fun inMemoryJournalTransactions_areLinearizable() {
        ModelCheckingOptions()
            .iterations(100)
            .threads(3)
            .actorsPerThread(3)
            .actorsBefore(0)
            .actorsAfter(0)
            .sequentialSpecification(JournalSequentialSpecification::class.java)
            .addGuarantee(
                forClasses(JournalViewNormalizer::class)
                    .allMethods()
                    .ignore(),
            )
            .addGuarantee(
                forClasses(LincheckRecordFactory::class)
                    .allMethods()
                    .ignore(),
            )
            .addCustomScenario {
                parallel {
                    thread {
                        actor(::appendA)
                        actor(::retireB)
                        actor(::hydrate)
                    }
                    thread {
                        actor(::appendB)
                        actor(::retireA)
                        actor(::hydrate)
                    }
                    thread {
                        actor(::confirmTwo)
                        actor(::prune)
                        actor(::hydrate)
                    }
                }
            }
            .check(this::class)
    }

    private suspend fun append(slot: String): JournalAppendResult =
        storage.transaction { transaction ->
            val client = requireNotNull(transaction.client(LINCHECK_CLIENT_ID))
            val sequence = client.lastAllocatedSequence + 1L
            transaction.advanceClient(
                records.client(client, lastAllocated = sequence),
            )
            transaction.insertIntent(
                recordVersion = 1,
                clientId = LINCHECK_CLIENT_ID,
                clientSequence = sequence,
                mutationId = mutationIdFor(slot),
                namespace = LINCHECK_NAMESPACE,
                canonicalId = "slot-$slot",
                mutatorId = LINCHECK_MUTATOR_ID,
                mutatorVersion = 1,
                argsBlob = slot.encodeToByteArray(),
                idempotencyRoot = "lincheck-$slot",
                createdAt = sequence,
            )
            transaction.insertExecution(
                records.execution(sequence, MutationExecutionPhase.UNPREPARED),
            )
            JournalAppendResult(slot, sequence)
        }

    private suspend fun retire(slot: String): JournalRetireResult =
        storage.transaction { transaction ->
            val intent =
                transaction.intents(LINCHECK_CLIENT_ID).firstOrNull { row ->
                    row.mutationId == mutationIdFor(slot)
                } ?: return@transaction JournalRetireResult(slot, null, JournalRetireOutcome.ABSENT)
            val previous =
                transaction.executions(LINCHECK_CLIENT_ID).single { row ->
                    row.clientSequence == intent.clientSequence
                }
            if (previous.phase == MutationExecutionPhase.RETIRED) {
                return@transaction JournalRetireResult(
                    slot,
                    intent.clientSequence,
                    JournalRetireOutcome.RETIRED,
                )
            }
            require(previous.phase == MutationExecutionPhase.UNPREPARED)
            val sequence = intent.clientSequence
            val client = requireNotNull(transaction.client(LINCHECK_CLIENT_ID))
            transaction.insertAttempt(
                records.attempt(
                    intent = intent,
                    slot = slot,
                    advertisedRetiredThroughSequence = client.retiredThroughSequence,
                ),
            )
            transaction.advanceExecution(
                records.execution(sequence, MutationExecutionPhase.READY, generation = 1),
            )
            transaction.advanceExecution(
                records.execution(sequence, MutationExecutionPhase.INFLIGHT, generation = 1),
            )
            transaction.insertAck(records.ack(sequence, slot))
            transaction.advanceExecution(
                records.execution(
                    sequence,
                    MutationExecutionPhase.ACKED,
                    generation = 1,
                    attempt = 1,
                    lastAttemptAt = 10L + sequence,
                ),
            )
            transaction.advanceExecution(
                records.execution(
                    sequence,
                    MutationExecutionPhase.EFFECTS_PENDING,
                    generation = 1,
                    attempt = 1,
                    lastAttemptAt = 10L + sequence,
                ),
            )
            transaction.advanceExecution(
                records.execution(
                    sequence,
                    MutationExecutionPhase.RETIRED,
                    generation = 1,
                    attempt = 1,
                    lastAttemptAt = 10L + sequence,
                    retiredAt = 20L + sequence,
                ),
            )

            val executions = transaction.executions(LINCHECK_CLIENT_ID)
            var prefix = client.retiredThroughSequence
            var executionIndex = 0
            while (executionIndex < executions.size) {
                val execution = executions[executionIndex]
                if (
                    execution.clientSequence == prefix + 1L &&
                    execution.phase == MutationExecutionPhase.RETIRED
                ) {
                    prefix += 1L
                }
                executionIndex += 1
            }
            if (prefix > client.retiredThroughSequence) {
                transaction.advanceClient(records.client(client, retiredThrough = prefix))
            }
            JournalRetireResult(slot, sequence, JournalRetireOutcome.RETIRED)
        }
}

data class JournalAppendResult(
    val slot: String,
    val sequence: Long,
)

enum class JournalRetireOutcome {
    ABSENT,
    RETIRED,
}

data class JournalRetireResult(
    val slot: String,
    val sequence: Long?,
    val outcome: JournalRetireOutcome,
)

data class JournalView(
    val lastAllocated: Long,
    val retiredThrough: Long,
    val serverConfirmedThrough: Long,
    val retained: List<RetainedIntent>,
    val orphanExecutionCount: Int,
    val orphanAttemptCount: Int,
    val orphanAckCount: Int,
    val orphanFailureCount: Int,
    val orphanEffectCount: Int,
    val aliases: List<String>,
    val tombstones: List<String>,
)

data class RetainedIntent(
    val slot: String,
    val sequence: Long,
    val rowId: Long,
    val args: List<Byte>,
    val phase: MutationExecutionPhase,
    val attemptGenerations: List<Int>,
    val ackGenerations: List<Int>,
    val effectDispositions: List<MutationEffectDisposition>,
)

class JournalSequentialSpecification {
    private var lastAllocated = 0L
    private var retiredThrough = 0L
    private var serverConfirmedThrough = 0L
    private val slots = linkedMapOf<String, ModelSlot>()

    suspend fun appendA(): JournalAppendResult = append(LINCHECK_SLOT_A)

    suspend fun appendB(): JournalAppendResult = append(LINCHECK_SLOT_B)

    suspend fun retireA(): JournalRetireResult = retire(LINCHECK_SLOT_A)

    suspend fun retireB(): JournalRetireResult = retire(LINCHECK_SLOT_B)

    suspend fun confirmTwo(): Long {
        serverConfirmedThrough = maxOf(serverConfirmedThrough, minOf(2L, retiredThrough))
        return serverConfirmedThrough
    }

    suspend fun prune(): JournalView {
        slots.values.forEach { slot ->
            if (slot.sequence <= serverConfirmedThrough) slot.retained = false
        }
        return view()
    }

    suspend fun hydrate(): JournalView = view()

    private fun append(slot: String): JournalAppendResult {
        check(slot !in slots)
        lastAllocated += 1L
        slots[slot] = ModelSlot(sequence = lastAllocated)
        return JournalAppendResult(slot, lastAllocated)
    }

    private fun retire(slot: String): JournalRetireResult {
        val row = slots[slot]?.takeIf(ModelSlot::retained)
            ?: return JournalRetireResult(slot, null, JournalRetireOutcome.ABSENT)
        row.retired = true
        while (slots.values.any { candidate -> candidate.sequence == retiredThrough + 1L && candidate.retired }) {
            retiredThrough += 1L
        }
        return JournalRetireResult(slot, row.sequence, JournalRetireOutcome.RETIRED)
    }

    private fun view(): JournalView =
        JournalViewNormalizer.validate(
            JournalView(
                lastAllocated = lastAllocated,
                retiredThrough = retiredThrough,
                serverConfirmedThrough = serverConfirmedThrough,
                retained =
                    slots
                        .map { (slot, row) -> slot to row }
                        .filter { (_, row) -> row.retained }
                        .sortedBy { (_, row) -> row.sequence }
                        .map { (slot, row) ->
                            RetainedIntent(
                                slot = slot,
                                sequence = row.sequence,
                                rowId = row.sequence,
                                args = slot.encodeToByteArray().toList(),
                                phase =
                                    if (row.retired) {
                                        MutationExecutionPhase.RETIRED
                                    } else {
                                        MutationExecutionPhase.UNPREPARED
                                    },
                                attemptGenerations = if (row.retired) listOf(1) else emptyList(),
                                ackGenerations = if (row.retired) listOf(1) else emptyList(),
                                effectDispositions = emptyList(),
                            )
                        },
                orphanExecutionCount = 0,
                orphanAttemptCount = 0,
                orphanAckCount = 0,
                orphanFailureCount = 0,
                orphanEffectCount = 0,
                aliases = emptyList(),
                tombstones = emptyList(),
            ),
        )
}

private class ModelSlot(
    val sequence: Long,
    var retired: Boolean = false,
    var retained: Boolean = true,
)

private fun seededLincheckStorage(): InMemoryMutationJournalStorage =
    InMemoryMutationJournalStorage().also { storage ->
        runBlocking {
            storage.transaction { transaction ->
                transaction.insertClient(
                    MutationClientRecord(
                        recordVersion = 1,
                        clientId = LINCHECK_CLIENT_ID,
                        lastAllocatedSequence = 0L,
                        retiredThroughSequence = 0L,
                        serverConfirmedRetiredThroughSequence = 0L,
                        createdAt = 0L,
                    ),
                )
            }
        }
    }

private data class JournalRows(
    val client: MutationClientRecord,
    val intents: List<MutationIntentRecord>,
    val executions: List<MutationExecutionRecord>,
    val attempts: List<MutationAttemptRecord>,
    val acknowledgements: List<MutationAckRecord>,
    val failures: List<MutationFailureRecord>,
    val effects: List<MutationEffectRecord>,
    val aliases: List<MutationKeyAliasRecord>,
    val tombstones: List<MutationKeyTombstoneRecord>,
)

private fun MutationJournalTransaction.toJournalRows(): JournalRows =
    JournalRows(
        client = requireNotNull(client(LINCHECK_CLIENT_ID)),
        intents = intents(LINCHECK_CLIENT_ID),
        executions = executions(LINCHECK_CLIENT_ID),
        attempts = attempts(LINCHECK_CLIENT_ID),
        acknowledgements = acks(LINCHECK_CLIENT_ID),
        failures = failures(LINCHECK_CLIENT_ID),
        effects = effects(LINCHECK_CLIENT_ID),
        aliases = aliases(),
        tombstones = tombstones(),
    )

/** Pure normalization deliberately excluded from managed scheduling; it reads detached rows only. */
private object JournalViewNormalizer {
    fun normalize(rows: JournalRows): JournalView =
        with(rows) {
            val sequences = intents.map { row -> row.clientSequence }.toSet()
            val executionsBySequence = executions.associateBy { row -> row.clientSequence }
            val attemptsBySequence = attempts.groupBy { row -> row.clientSequence }
            val acksBySequence = acknowledgements.groupBy { row -> row.clientSequence }
            val effectsBySequence = effects.groupBy { row -> row.clientSequence }

            validate(
                JournalView(
                    lastAllocated = client.lastAllocatedSequence,
                    retiredThrough = client.retiredThroughSequence,
                    serverConfirmedThrough = client.serverConfirmedRetiredThroughSequence,
                    retained =
                        intents.sortedBy { row -> row.clientSequence }.map { intent ->
                            val slot = slotForMutationId(intent.mutationId)
                            RetainedIntent(
                                slot = slot,
                                sequence = intent.clientSequence,
                                rowId = intent.rowId,
                                args = intent.argsBlob.toList(),
                                phase = requireNotNull(executionsBySequence[intent.clientSequence]).phase,
                                attemptGenerations =
                                    attemptsBySequence[intent.clientSequence].orEmpty().map { row -> row.generation },
                                ackGenerations =
                                    acksBySequence[intent.clientSequence].orEmpty().map { row -> row.generation },
                                effectDispositions =
                                    effectsBySequence[intent.clientSequence].orEmpty().map { row -> row.disposition },
                            )
                        },
                    orphanExecutionCount = executions.count { row -> row.clientSequence !in sequences },
                    orphanAttemptCount = attempts.count { row -> row.clientSequence !in sequences },
                    orphanAckCount = acknowledgements.count { row -> row.clientSequence !in sequences },
                    orphanFailureCount = failures.count { row -> row.clientSequence !in sequences },
                    orphanEffectCount = effects.count { row -> row.clientSequence !in sequences },
                    aliases =
                        aliases.map { row ->
                            "${row.sourceNamespace}:${row.sourceCanonicalId}->" +
                                "${row.targetNamespace}:${row.targetCanonicalId}:${row.state}"
                        },
                    tombstones =
                        tombstones.map { row ->
                            "${row.namespace}:${row.canonicalId}:${row.createdByClientId}:" +
                                "${row.createdBySequence}:${row.state}"
                        },
                ),
            )
        }

    fun validate(view: JournalView): JournalView =
        view.also {
            check(it.serverConfirmedThrough >= 0L)
            check(it.serverConfirmedThrough <= it.retiredThrough)
            check(it.retiredThrough >= 0L)
            check(it.retiredThrough <= it.lastAllocated)
            check(it.orphanExecutionCount == 0)
            check(it.orphanAttemptCount == 0)
            check(it.orphanAckCount == 0)
            check(it.orphanFailureCount == 0)
            check(it.orphanEffectCount == 0)

            var previousSequence = 0L
            var retainedIndex = 0
            while (retainedIndex < it.retained.size) {
                val row = it.retained[retainedIndex]
                check(row.sequence > previousSequence)
                check(row.sequence >= 1L)
                check(row.sequence <= it.lastAllocated)
                if (row.sequence <= it.retiredThrough) {
                    check(row.phase == MutationExecutionPhase.RETIRED)
                }
                if (row.sequence == it.retiredThrough + 1L) {
                    check(row.phase != MutationExecutionPhase.RETIRED)
                }
                if (row.phase == MutationExecutionPhase.RETIRED) {
                    check(row.attemptGenerations.size == 1)
                    check(row.attemptGenerations[0] == 1)
                    check(row.ackGenerations.size == 1)
                    check(row.ackGenerations[0] == 1)
                    var effectIndex = 0
                    while (effectIndex < row.effectDispositions.size) {
                        check(row.effectDispositions[effectIndex] != MutationEffectDisposition.PENDING)
                        effectIndex += 1
                    }
                }
                previousSequence = row.sequence
                retainedIndex += 1
            }
        }
}

/**
 * Pure immutable carrier construction excluded from managed scheduling. The storage, transaction
 * lambda, mutex, reads, state transitions, and retired-prefix scan remain fully instrumented.
 */
private class LincheckRecordFactory {
    fun attempt(
        intent: MutationIntentRecord,
        slot: String,
        advertisedRetiredThroughSequence: Long,
    ): MutationAttemptRecord =
        MutationAttemptRecord(
            clientId = LINCHECK_CLIENT_ID,
            clientSequence = intent.clientSequence,
            generation = 1,
            effectiveNamespace = intent.namespace,
            effectiveCanonicalId = intent.canonicalId,
            valueCodecVersion = 1,
            basePresence = MutationPresenceState.PRESENT,
            baseBlob = "base-$slot".encodeToByteArray(),
            minePresence = MutationPresenceState.PRESENT,
            mineBlob = "mine-$slot".encodeToByteArray(),
            preconditionMetaPresent = false,
            preconditionWrittenAt = null,
            preconditionEtag = null,
            advertisedRetiredThroughSequence = advertisedRetiredThroughSequence,
            generationIdempotencyKey = "lincheck-$slot-g1",
            preparedAt = 5L + intent.clientSequence,
            conflictMetaPresent = null,
            conflictWrittenAt = null,
            conflictEtag = null,
            conflictReceivedAt = null,
        )

    fun ack(
        sequence: Long,
        slot: String,
    ): MutationAckRecord =
        MutationAckRecord(
            clientId = LINCHECK_CLIENT_ID,
            clientSequence = sequence,
            generation = 1,
            authoritativePresence = MutationPresenceState.PRESENT,
            authoritativeBlob = "ack-$slot".encodeToByteArray(),
            valueCodecVersion = 1,
            etag = "etag-$slot",
            canonicalTargetNamespace = null,
            canonicalTargetId = null,
            receivedAt = 10L + sequence,
        )

    fun execution(
        sequence: Long,
        phase: MutationExecutionPhase,
        generation: Int = 0,
        attempt: Int = 0,
        lastAttemptAt: Long? = null,
        retiredAt: Long? = null,
    ): MutationExecutionRecord =
        MutationExecutionRecord(
            clientId = LINCHECK_CLIENT_ID,
            clientSequence = sequence,
            phase = phase,
            currentGeneration = generation,
            attempt = attempt,
            lastAttemptAt = lastAttemptAt,
            activeFailureId = null,
            retiredAt = retiredAt,
        )

    fun client(
        source: MutationClientRecord,
        lastAllocated: Long = source.lastAllocatedSequence,
        retiredThrough: Long = source.retiredThroughSequence,
    ): MutationClientRecord =
        MutationClientRecord(
            recordVersion = source.recordVersion,
            clientId = source.clientId,
            lastAllocatedSequence = lastAllocated,
            retiredThroughSequence = retiredThrough,
            serverConfirmedRetiredThroughSequence = source.serverConfirmedRetiredThroughSequence,
            createdAt = source.createdAt,
        )
}

private fun mutationIdFor(slot: String): String = "lincheck-$slot"

private fun slotForMutationId(mutationId: String): String =
    mutationId.removePrefix("lincheck-")

private const val LINCHECK_CLIENT_ID: String = "lincheck-client"
private const val LINCHECK_NAMESPACE: String = "lincheck"
private const val LINCHECK_MUTATOR_ID: String = "lincheck-mutator"
private const val LINCHECK_SLOT_A: String = "A"
private const val LINCHECK_SLOT_B: String = "B"
