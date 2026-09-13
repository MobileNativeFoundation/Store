package org.mobilenativefoundation.store6.mutations.drain

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/** OS execution constraints for a scheduled drain activation. */
@ExperimentalStoreApi
public class DrainConstraints(
    /** Require network connectivity before an activation runs. Default true. */
    @ExperimentalStoreApi
    public val requiresNetwork: Boolean = true,

    /** Require external power before an activation runs. Default false. */
    @ExperimentalStoreApi
    public val requiresCharging: Boolean = false,
)
