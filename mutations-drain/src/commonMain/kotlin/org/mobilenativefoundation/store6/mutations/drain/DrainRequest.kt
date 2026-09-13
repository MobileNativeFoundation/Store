package org.mobilenativefoundation.store6.mutations.drain

import kotlin.time.Duration
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/** One request for one future constraint-gated activation. */
@ExperimentalStoreApi
public class DrainRequest(
    /** The coordinator registration name, stable across process restarts. */
    @ExperimentalStoreApi
    public val storeName: String,

    @ExperimentalStoreApi
    public val constraints: DrainConstraints,

    /** Earliest execution delay from now. [Duration.ZERO] means as soon as constraints hold. */
    @ExperimentalStoreApi
    public val earliestDelay: Duration,
)
