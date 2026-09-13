package org.mobilenativefoundation.store6.core.seam

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * Opaque freshness evidence captured before sending an acknowledgement-producing request.
 *
 * Evidence belongs to one canonical key in one Store runtime. Later key, namespace, or global
 * invalidation revokes its freshness authority. Eviction, Store restart, and use for an alias's
 * different target also expire it. Retransmissions of the same logical request and adoption retries
 * retain the original evidence. Capture new evidence only for a new logical request. Evidence does
 * not retain the key engine or survive restart.
 */
@ExperimentalStoreApi
public class FreshnessEvidence internal constructor(
    internal val engineIdentity: Any,
    internal val staleEpoch: Long,
    internal val namespaceVersion: Any?,
    internal val globalVersion: Any,
)
