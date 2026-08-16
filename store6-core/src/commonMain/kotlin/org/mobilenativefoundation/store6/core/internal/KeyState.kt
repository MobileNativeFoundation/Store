package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreMeta

/**
 * Consume-once attribution owned by the exact fetch [owner] for a future pipeline emission.
 *
 * This tag applies only when a future pipeline row `==` [value]. A different or absent emission
 * consumes and discards it; equal external content is indistinguishable residue. Matching writer
 * rows remain outside residence until [owner] publishes an exact durable commit disposition.
 */
internal class AttributionTag(
    val owner: FetchTicket,
    val value: Any,
    val origin: Origin,
    val meta: StoreMeta,
    val staleEpochAtCommit: Long,
)

/** Immutable state governing fetch ownership and invalidation epochs for one canonical key. */
internal data class KeyState(
    /** The current fetch slot for the key. */
    val fetch: FetchSlot,

    /** Monotone count of invalidations; a resident value is stale when committed under a lower epoch. */
    val staleEpoch: Long,

    /** Monotone count of clears; guards fetch commits so a pre-clear response can never resurrect. */
    val clearEpoch: Long,

    /** Monotone generation of the future source-of-truth reader pipeline. */
    val readerGen: Long,

    /** Consume-once provenance for a future pipeline emission matching its stamped value. */
    val attribution: AttributionTag?,
) {
    companion object {
        /** State for a key with no active fetch and no invalidation history. */
        val Initial: KeyState =
            KeyState(
                fetch = FetchSlot.Idle,
                staleEpoch = 0L,
                clearEpoch = 0L,
                readerGen = 0L,
                attribution = null,
            )
    }
}
