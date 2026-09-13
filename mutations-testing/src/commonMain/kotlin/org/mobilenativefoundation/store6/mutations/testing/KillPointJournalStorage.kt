@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations.testing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalTransaction

/** Semantic journal transaction boundaries available to deterministic crash tests. */
@ExperimentalStoreApi
public enum class JournalStorageKillPoint {
    BEFORE_RETIREMENT_FINALIZATION_COMMIT,
    AFTER_RETIREMENT_FINALIZATION_COMMIT,
    BEFORE_PRUNE,
    BEFORE_PRUNE_COMMIT,
    AFTER_PRUNE_COMMIT,
}

/** One-shot simulated process crash raised by [KillPointJournalStorage]. */
@ExperimentalStoreApi
public class JournalStorageCrashException(
    /** The semantic boundary that aborted the caller. */
    public val killPoint: JournalStorageKillPoint,
) : RuntimeException("Simulated journal crash at $killPoint")

/**
 * One-shot deterministic crash decorator for [MutationJournalStorage].
 *
 * [arm] rejects a second armed point. The selected point remains armed across unrelated
 * transactions and clears before its crash is raised, so reopening or retrying cannot trip it
 * again. Classification uses the transaction-entry execution phase and the operation invoked;
 * it never depends on transaction ordinals or scheduler timing.
 */
@ExperimentalStoreApi
public class KillPointJournalStorage(
    private val delegate: MutationJournalStorage,
) : MutationJournalStorage {
    private val gate: Mutex = Mutex()
    private var armed: JournalStorageKillPoint? = null

    /** Arms exactly one semantic transaction boundary. */
    public suspend fun arm(killPoint: JournalStorageKillPoint) {
        gate.withLock {
            check(armed == null) { "A journal kill point is already armed: $armed" }
            armed = killPoint
        }
    }

    override suspend fun <R> transaction(
        block: (MutationJournalTransaction) -> R,
    ): R =
        gate.withLock {
            var committedRetirementFinalization = false
            var committedPrune = false
            val result =
                delegate.transaction { transaction ->
                    val observed =
                        ObservingJournalTransaction(
                            delegate = transaction,
                            beforePrune = {
                                if (armed == JournalStorageKillPoint.BEFORE_PRUNE) {
                                    crash(JournalStorageKillPoint.BEFORE_PRUNE)
                                }
                            },
                        )
                    val value = block(observed)
                    if (
                        observed.sawRetirementFinalization &&
                        armed == JournalStorageKillPoint.BEFORE_RETIREMENT_FINALIZATION_COMMIT
                    ) {
                        crash(JournalStorageKillPoint.BEFORE_RETIREMENT_FINALIZATION_COMMIT)
                    }
                    if (observed.sawPrune && armed == JournalStorageKillPoint.BEFORE_PRUNE_COMMIT) {
                        crash(JournalStorageKillPoint.BEFORE_PRUNE_COMMIT)
                    }
                    committedRetirementFinalization = observed.sawRetirementFinalization
                    committedPrune = observed.sawPrune
                    value
                }
            if (
                committedRetirementFinalization &&
                armed == JournalStorageKillPoint.AFTER_RETIREMENT_FINALIZATION_COMMIT
            ) {
                crash(JournalStorageKillPoint.AFTER_RETIREMENT_FINALIZATION_COMMIT)
            }
            if (committedPrune && armed == JournalStorageKillPoint.AFTER_PRUNE_COMMIT) {
                crash(JournalStorageKillPoint.AFTER_PRUNE_COMMIT)
            }
            result
        }

    private fun crash(killPoint: JournalStorageKillPoint): Nothing {
        check(armed == killPoint)
        armed = null
        throw JournalStorageCrashException(killPoint)
    }
}

private class ObservingJournalTransaction(
    private val delegate: MutationJournalTransaction,
    private val beforePrune: () -> Unit,
) : MutationJournalTransaction by delegate {
    private val entryPhases = mutableMapOf<Pair<String, Long>, MutationExecutionPhase>()

    var sawRetirementFinalization: Boolean = false
        private set
    var sawPrune: Boolean = false
        private set

    override fun advanceExecution(record: MutationExecutionRecord) {
        val identity = record.clientId to record.clientSequence
        val entryPhase =
            entryPhases.getOrPut(identity) {
                requireNotNull(
                    delegate.executions(record.clientId).firstOrNull { previous ->
                        previous.clientSequence == record.clientSequence
                    },
                ).phase
            }
        delegate.advanceExecution(record)
        if (
            entryPhase == MutationExecutionPhase.EFFECTS_PENDING &&
            record.phase == MutationExecutionPhase.RETIRED
        ) {
            sawRetirementFinalization = true
        }
    }

    override fun prune(
        clientId: String,
        serverConfirmedRetiredThroughSequence: Long,
    ) {
        beforePrune()
        delegate.prune(clientId, serverConfirmedRetiredThroughSequence)
        sawPrune = true
    }
}
