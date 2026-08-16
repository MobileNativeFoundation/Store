package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreMeta

/** An immutable resident value paired with the provenance needed for honest emissions. */
internal data class ValueEnvelope<V : Any>(
    val value: V,
    val origin: Origin,

    /**
     * Freshness metadata recorded when this value was committed, or `null` when provenance is
     * unknown (an external source-of-truth row or a hydrated pre-existing row). Null meta is a
     * conservative posture: the value reports `isStale = true`, age zero, and never
     * satisfies demand without a revalidation (see the null-meta planning rule).
     */
    val meta: StoreMeta?,

    /** The key's stale epoch stamped when this envelope was produced. */
    val staleEpochAtCommit: Long,

    /** Ticket whose no-row 304 direct-send path exclusively owns this exact envelope identity. */
    val directRevalidationOwner: FetchTicket? = null,
)
