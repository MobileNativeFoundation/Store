package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import kotlin.time.Duration

/** Describes whether a key currently owns a fetch operation. */
internal sealed interface FetchSlot {
    /** No fetch is active for the key. */
    data object Idle : FetchSlot

    /** A fetch is active and represented by [ticket]. */
    class InFlight(
        val ticket: FetchTicket,

        /** The key's clear epoch when this fetch launched; a later clear supersedes the commit. */
        val clearEpochAtLaunch: Long,
    ) : FetchSlot
}

/**
 * Identifies one fetch attempt and exposes its one-shot completion to all waiters.
 *
 * [outcome] reaches a terminal state either when the owning coroutine publishes its result or
 * when the parent engine begins cancellation.
 */
@OptIn(ExperimentalStoreApi::class)
internal class FetchTicket(
    val outcome: CompletableDeferred<FetchOutcome>,

    /** Residence revision whose demand this ticket reserved, including an absent revision. */
    val requestRevision: Long = 0L,

    /** Residence revision used as the baseline for a NotModified response, when one existed. */
    val residenceRevisionAtLaunch: Long? = null,

    /** Exact resident envelope captured with the nullable NotModified baseline. */
    val residenceEnvelopeAtLaunch: Any? = null,

    /** Stale epoch used to plan this ticket's pre-fetch baseline. */
    val staleEpochAtLaunch: Long = 0L,

    /** Wall-clock instant used to plan this ticket's pre-fetch baseline. */
    val nowEpochMillisAtLaunch: Long = 0L,

    /** Durable bookkeeping posture used to plan this ticket's pre-fetch baseline. */
    val statusAtLaunch: KeyStatus? = null,
) {
    /**
     * KMP-safe early classification published with slot settlement, before ordered outcome tails.
     * Reader delivery uses it to preserve ticket ownership and wake a durable causal commit row;
     * [outcome] remains authoritative.
     */
    val disposition = MutableStateFlow<FetchDisposition>(FetchDisposition.InFlight)
}

/** Slot-settled ticket identity visible before persistence/bookkeeping tails complete. */
internal sealed interface FetchDisposition {
    data object InFlight : FetchDisposition

    class Committing(
        val attribution: AttributionTag,
        /** Completed-write sequence visible before this write returned from persistence. */
        val successfulWriteSequenceAtStart: Long,
    ) : FetchDisposition

    class Committed(
        val successfulWriteSequence: Long,
        val attribution: AttributionTag,
        /** Reader generation whose raw observations were closed by this commit. */
        val rawReaderGen: Long,
        /** Latest raw observation ordered before the durable commit boundary. */
        val rawCommitCutoff: Long,
        /** Exact pre-return raw observation authorized after convergence, when one exists. */
        val authoritativeRawSequence: Long?,
    ) : FetchDisposition

    class Revalidated(
        val envelope: Any,
    ) : FetchDisposition

    data object Deleted : FetchDisposition

    data object Failed : FetchDisposition

    data object Cancelled : FetchDisposition

    data object ObsoleteRevalidation : FetchDisposition

    data object Superseded : FetchDisposition
}

/** The terminal result of a fetch attempt. */
internal sealed interface FetchOutcome {
    /** The fetched value was committed before the outcome became observable. */
    class Committed(
        val value: Any,
        /** Successful SoT-write sequence that became current before this outcome was published. */
        val successfulWriteSequence: Long,
        /** Exact tag stamped for this commit, used to classify observations made during write. */
        val attribution: AttributionTag,
        /** Reader generation whose raw observations were closed by this commit. */
        val rawReaderGen: Long,
        /** Latest raw observation ordered before the durable commit boundary. */
        val rawCommitCutoff: Long,
        /** Exact pre-return raw observation authorized after convergence, when one exists. */
        val authoritativeRawSequence: Long?,
    ) : FetchOutcome

    /** The fetch failed with [exception] at [atEpochMillis]. */
    class Failed(
        val exception: StoreException,
        val atEpochMillis: Long,
        val bookkeepingRecorded: Boolean = false,
    ) : FetchOutcome

    /** The fetch succeeded but a clear advanced the clear epoch after launch; the value was discarded. */
    data object Superseded : FetchOutcome

    /** The fetcher reported not-modified; resident metadata was refreshed in place. */
    class Revalidated(
        val residenceRevision: Long,
        /** Exact refreshed envelope; same-value reader replays preserve this identity. */
        val envelope: Any,
        /** Elapsed time since the value's previous commit, measured at revalidation. */
        val age: Duration,
    ) : FetchOutcome

    /** A NotModified baseline changed before commit and must be planned again. */
    data object ObsoleteRevalidation : FetchOutcome

    /** The fetcher reported server-side deletion; [residenceRevision] names exact absence. */
    class Deleted(
        val residenceRevision: Long,
    ) : FetchOutcome
}
