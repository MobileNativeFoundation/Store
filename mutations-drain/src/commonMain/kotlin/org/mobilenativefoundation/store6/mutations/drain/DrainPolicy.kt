package org.mobilenativefoundation.store6.mutations.drain

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/** Per-store scheduling policy. */
@ExperimentalStoreApi
public class DrainPolicy(
    @ExperimentalStoreApi
    public val constraints: DrainConstraints = DrainConstraints(),
    @ExperimentalStoreApi
    public val backoff: DrainBackoff = DrainBackoff(),

    /**
     * When true, `watch` runs an immediate in-process pass on every journal enqueue. This
     * fast path does not persist a pre-pass safety activation. When false, `watch` only
     * schedules an activation with `Duration.ZERO` delay. Default true.
     */
    @ExperimentalStoreApi
    public val drainOnEnqueue: Boolean = true,
)
