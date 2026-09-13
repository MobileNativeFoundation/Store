@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.drain.internal

import org.mobilenativefoundation.store6.mutations.MutationPendingState
import org.mobilenativefoundation.store6.mutations.PendingIntent
import org.mobilenativefoundation.store6.mutations.drain.DrainBackoff
import kotlin.time.Duration

/** One row's inspection-visible fields; mutationId keys the journal fingerprint. */
internal data class PendingFingerprint(
    val namespace: String,
    val canonicalId: String,
    val mutatorId: String,
    val state: MutationPendingState,
    val attempt: Int,
    val createdAtEpochMillis: Long,
)

internal typealias JournalFingerprint = Map<String, PendingFingerprint>

internal class DerivationState(
    val previousFingerprint: JournalFingerprint?,
    val noProgressPasses: Int,
)

internal class DerivationResult(
    val delay: Duration?,
    val pendingIntents: Int,
    val nextState: DerivationState,
)

internal fun deriveFollowUp(
    rows: List<PendingIntent>,
    checkpointFailed: Boolean,
    backoff: DrainBackoff,
    state: DerivationState,
): DerivationResult {
    val fingerprint =
        rows.associate { row ->
            row.mutationId to
                PendingFingerprint(
                    namespace = row.namespace,
                    canonicalId = row.canonicalId,
                    mutatorId = row.mutatorId,
                    state = row.state,
                    attempt = row.attempt,
                    createdAtEpochMillis = row.createdAtEpochMillis,
                )
        }
    val noProgressPasses =
        if (state.previousFingerprint == null || state.previousFingerprint != fingerprint) {
            0
        } else {
            state.noProgressPasses + 1
        }
    val escalation =
        if (noProgressPasses == 0) {
            Duration.ZERO
        } else {
            backoff.delayFor(noProgressPasses)
        }
    val delay =
        when {
            rows.isEmpty() && !checkpointFailed -> null
            rows.isEmpty() -> maxOf(backoff.initialDelay, escalation)
            else -> {
                val identities = mutableSetOf<Pair<String, String>>()
                val derived =
                    rows
                        .filter { row -> identities.add(row.namespace to row.canonicalId) }
                        .minOf { row ->
                            when (row.state) {
                                MutationPendingState.INFLIGHT,
                                MutationPendingState.ADOPTING,
                                MutationPendingState.APPLYING_EFFECTS,
                                -> Duration.ZERO
                                MutationPendingState.PENDING,
                                MutationPendingState.REFRESHING,
                                -> if (row.attempt == 0) {
                                    Duration.ZERO
                                } else {
                                    backoff.delayFor(row.attempt)
                                }
                            }
                        }
                maxOf(derived, escalation)
            }
        }

    return DerivationResult(
        delay = delay,
        pendingIntents = rows.size,
        nextState =
            DerivationState(
                previousFingerprint = fingerprint,
                noProgressPasses = noProgressPasses,
            ),
    )
}
