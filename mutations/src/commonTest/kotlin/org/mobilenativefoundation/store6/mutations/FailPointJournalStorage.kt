@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectDisposition
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalTransaction

/** One-shot failure raised inside a selected journal transaction. */
internal class FailPointTransactionException :
    RuntimeException("Injected semantic journal transaction failure")

/** Simulated process death immediately before or immediately after a selected commit. */
internal class FailPointProcessDeathException(
    internal val committed: Boolean,
) : CancellationException(
        if (committed) {
            "Injected process death after semantic journal transaction commit"
        } else {
            "Injected process death before semantic journal transaction commit"
        },
    )

internal sealed interface JournalFailPoint

/** Engine transition points retained as the original closed four-entry enum. */
internal enum class JournalFailPointBoundary : JournalFailPoint {
    ACK_RECEIPT,
    ADOPTION_ADVANCE,
    EFFECT_MARKER,
    FINALIZATION,

    ;

    companion object {
        internal val CHECKPOINT_CONFIRMATION: JournalFailPoint =
            JournalStorageOperationFailPoint.CHECKPOINT_CONFIRMATION
        internal val BEFORE_PRUNE: JournalFailPoint = JournalStorageOperationFailPoint.BEFORE_PRUNE
        internal val PRUNE_COMMIT: JournalFailPoint = JournalStorageOperationFailPoint.PRUNE_COMMIT
    }
}

/**
 * Operation points kept outside [JournalFailPointBoundary] so its existing exhaustive callers do
 * not widen. Together, `FINALIZATION` before/after, `BEFORE_PRUNE`, and `PRUNE_COMMIT`
 * before/after are the exact local equivalents of C5's five frozen testing-kit modes.
 */
private enum class JournalStorageOperationFailPoint : JournalFailPoint {
    CHECKPOINT_CONFIRMATION,
    BEFORE_PRUNE,
    PRUNE_COMMIT,
}

private enum class JournalFailPointMode {
    FAIL_TRANSACTION,
    KILL_BEFORE_COMMIT,
    KILL_AFTER_COMMIT,
}

private data class ArmedJournalTransition(
    val predicate: (before: MutationExecutionRecord, after: MutationExecutionRecord) -> Boolean,
    val mode: JournalFailPointMode,
    val remainingMatches: Int,
)

private data class ArmedJournalFailPoint(
    val boundary: JournalFailPoint,
    val mode: JournalFailPointMode,
    val remainingMatches: Int,
)

/**
 * Test-only journal decorator for a semantic transaction failure or process death.
 *
 * The predicate observes an execution row immediately before and after a requested transition.
 * An unrelated or rolled-back non-terminal match leaves the fail point armed. Transaction-failure
 * and kill-before modes clear immediately and roll back; kill-after clears only after the delegate
 * commits and then raises the process-death analogue outside that transaction.
 */
internal class FailPointJournalStorage(
    private val delegate: MutationJournalStorage,
) : MutationJournalStorage {
    private val gate = Mutex()
    private var armedTransition: ArmedJournalTransition? = null
    private var armedBoundary: ArmedJournalFailPoint? = null

    internal val triggeredBoundaries: MutableList<JournalFailPointBoundary> = mutableListOf()

    internal val hasArmedFailPoint: Boolean
        get() {
            check(gate.tryLock()) { "Cannot inspect a fail point while a transaction is active." }
            return try {
                armedTransition != null || armedBoundary != null
            } finally {
                gate.unlock()
            }
        }

    internal fun armFailTransaction(
        predicate: (before: MutationExecutionRecord, after: MutationExecutionRecord) -> Boolean,
    ) {
        armTransition(predicate, JournalFailPointMode.FAIL_TRANSACTION, occurrence = 1)
    }

    internal fun armKillBeforeCommit(
        predicate: (before: MutationExecutionRecord, after: MutationExecutionRecord) -> Boolean,
        occurrence: Int = 1,
    ) {
        armTransition(predicate, JournalFailPointMode.KILL_BEFORE_COMMIT, occurrence)
    }

    internal fun armKillAfterCommit(
        predicate: (before: MutationExecutionRecord, after: MutationExecutionRecord) -> Boolean,
        occurrence: Int = 1,
    ) {
        armTransition(predicate, JournalFailPointMode.KILL_AFTER_COMMIT, occurrence)
    }

    internal fun armFailTransaction(
        boundary: JournalFailPoint,
        occurrence: Int = 1,
    ) {
        armBoundary(boundary, JournalFailPointMode.FAIL_TRANSACTION, occurrence)
    }

    internal fun armKillBeforeCommit(
        boundary: JournalFailPoint,
        occurrence: Int = 1,
    ) {
        armBoundary(boundary, JournalFailPointMode.KILL_BEFORE_COMMIT, occurrence)
    }

    internal fun armKillAfterCommit(
        boundary: JournalFailPoint,
        occurrence: Int = 1,
    ) {
        armBoundary(boundary, JournalFailPointMode.KILL_AFTER_COMMIT, occurrence)
    }

    private fun armTransition(
        predicate: (before: MutationExecutionRecord, after: MutationExecutionRecord) -> Boolean,
        mode: JournalFailPointMode,
        occurrence: Int,
    ) {
        require(occurrence > 0) { "Fail-point occurrence must be positive." }
        check(gate.tryLock()) { "Cannot arm a fail point while a transaction is active." }
        try {
            check(armedTransition == null && armedBoundary == null) {
                "A journal fail point is already armed."
            }
            armedTransition = ArmedJournalTransition(predicate, mode, occurrence)
        } finally {
            gate.unlock()
        }
    }

    private fun armBoundary(
        boundary: JournalFailPoint,
        mode: JournalFailPointMode,
        occurrence: Int,
    ) {
        require(occurrence > 0) { "Fail-point occurrence must be positive." }
        require(
            boundary != JournalFailPointBoundary.BEFORE_PRUNE ||
                mode == JournalFailPointMode.KILL_BEFORE_COMMIT
        ) { "BEFORE_PRUNE supports only kill-before semantics." }
        check(gate.tryLock()) { "Cannot arm a fail point while a transaction is active." }
        try {
            check(armedTransition == null && armedBoundary == null) {
                "A journal fail point is already armed."
            }
            armedBoundary = ArmedJournalFailPoint(boundary, mode, occurrence)
        } finally {
            gate.unlock()
        }
    }

    override suspend fun <R> transaction(block: (MutationJournalTransaction) -> R): R =
        gate.withLock {
            var transitionMatches = 0
            var boundaryMatches = 0
            var terminalTransitionMatched = false
            var terminalBoundaryMatched = false
            var killAfterCommit = false

            fun transitionMode(
                before: MutationExecutionRecord,
                after: MutationExecutionRecord,
            ): JournalFailPointMode? {
                val armed = armedTransition ?: return null
                if (terminalTransitionMatched || !armed.predicate(before, after)) return null
                transitionMatches += 1
                if (transitionMatches < armed.remainingMatches) return null
                terminalTransitionMatched = true
                return armed.mode
            }

            fun boundaryMode(boundary: JournalFailPoint): JournalFailPointMode? {
                val armed = armedBoundary ?: return null
                if (terminalBoundaryMatched || armed.boundary != boundary) return null
                boundaryMatches += 1
                if (boundaryMatches < armed.remainingMatches) return null
                terminalBoundaryMatched = true
                return armed.mode
            }

            fun recordBoundary(boundary: JournalFailPoint) {
                if (boundary is JournalFailPointBoundary) {
                    triggeredBoundaries += boundary
                }
            }

            fun handleTransition(mode: JournalFailPointMode?) {
                when (mode) {
                    JournalFailPointMode.FAIL_TRANSACTION -> {
                        armedTransition = null
                        throw FailPointTransactionException()
                    }
                    JournalFailPointMode.KILL_BEFORE_COMMIT -> {
                        armedTransition = null
                        throw FailPointProcessDeathException(committed = false)
                    }
                    JournalFailPointMode.KILL_AFTER_COMMIT -> killAfterCommit = true
                    null -> Unit
                }
            }

            fun handleBoundary(
                boundary: JournalFailPoint,
                mode: JournalFailPointMode?,
            ) {
                when (mode) {
                    JournalFailPointMode.FAIL_TRANSACTION -> {
                        armedBoundary = null
                        recordBoundary(boundary)
                        throw FailPointTransactionException()
                    }
                    JournalFailPointMode.KILL_BEFORE_COMMIT -> {
                        armedBoundary = null
                        recordBoundary(boundary)
                        throw FailPointProcessDeathException(committed = false)
                    }
                    JournalFailPointMode.KILL_AFTER_COMMIT -> killAfterCommit = true
                    null -> Unit
                }
            }

            val result =
                delegate.transaction { transaction ->
                    block(
                        object : MutationJournalTransaction by transaction {
                            override fun advanceExecution(record: MutationExecutionRecord) {
                                val before =
                                    transaction.executions(record.clientId).single {
                                        it.clientSequence == record.clientSequence
                                    }
                                handleTransition(transitionMode(before, record))
                                boundaryFor(before, record)?.let { boundary ->
                                    handleBoundary(boundary, boundaryMode(boundary))
                                }
                                transaction.advanceExecution(record)
                            }

                            override fun advanceEffect(record: MutationEffectRecord) {
                                val before =
                                    transaction.effects(record.clientId).single {
                                        it.clientSequence == record.clientSequence &&
                                            it.effectIndex == record.effectIndex
                                    }
                                if (
                                    before.disposition == MutationEffectDisposition.PENDING &&
                                    record.disposition == MutationEffectDisposition.APPLIED
                                ) {
                                    val boundary = JournalFailPointBoundary.EFFECT_MARKER
                                    handleBoundary(boundary, boundaryMode(boundary))
                                }
                                transaction.advanceEffect(record)
                            }

                            override fun confirmRetiredThrough(
                                clientId: String,
                                requestedThroughSequence: Long,
                                serverConfirmedThroughSequence: Long,
                            ): MutationClientRecord {
                                val persisted =
                                    transaction.confirmRetiredThrough(
                                        clientId,
                                        requestedThroughSequence,
                                        serverConfirmedThroughSequence,
                                    )
                                val boundary = JournalFailPointBoundary.CHECKPOINT_CONFIRMATION
                                handleBoundary(boundary, boundaryMode(boundary))
                                return persisted
                            }

                            override fun prune(
                                clientId: String,
                                serverConfirmedRetiredThroughSequence: Long,
                            ) {
                                val beforePrune = JournalFailPointBoundary.BEFORE_PRUNE
                                handleBoundary(beforePrune, boundaryMode(beforePrune))
                                transaction.prune(clientId, serverConfirmedRetiredThroughSequence)
                                val pruneCommit = JournalFailPointBoundary.PRUNE_COMMIT
                                handleBoundary(pruneCommit, boundaryMode(pruneCommit))
                            }
                        },
                    )
                }

            if (transitionMatches > 0) {
                val armed = checkNotNull(armedTransition)
                if (terminalTransitionMatched) {
                    check(armed.mode == JournalFailPointMode.KILL_AFTER_COMMIT)
                    armedTransition = null
                } else {
                    armedTransition =
                        armed.copy(
                            remainingMatches = armed.remainingMatches - transitionMatches,
                        )
                }
            }
            if (boundaryMatches > 0) {
                val armed = checkNotNull(armedBoundary)
                if (terminalBoundaryMatched) {
                    check(armed.mode == JournalFailPointMode.KILL_AFTER_COMMIT)
                    armedBoundary = null
                    recordBoundary(armed.boundary)
                } else {
                    armedBoundary =
                        armed.copy(
                            remainingMatches = armed.remainingMatches - boundaryMatches,
                        )
                }
            }
            if (killAfterCommit) {
                throw FailPointProcessDeathException(committed = true)
            }
            result
        }

    private fun boundaryFor(
        before: MutationExecutionRecord,
        after: MutationExecutionRecord,
    ): JournalFailPointBoundary? =
        when {
            before.phase == MutationExecutionPhase.INFLIGHT &&
                after.phase == MutationExecutionPhase.ACKED ->
                JournalFailPointBoundary.ACK_RECEIPT

            before.phase == MutationExecutionPhase.ACKED &&
                after.phase == MutationExecutionPhase.EFFECTS_PENDING ->
                JournalFailPointBoundary.ADOPTION_ADVANCE

            before.phase == MutationExecutionPhase.EFFECTS_PENDING &&
                after.phase == MutationExecutionPhase.RETIRED ->
                JournalFailPointBoundary.FINALIZATION

            else -> null
        }
}
