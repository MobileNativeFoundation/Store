@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations.drain

import kotlin.time.Clock
import org.mobilenativefoundation.store6.core.seam.WallClock

/**
 * Drain-owned production clock used when the coordinator's `wallClock` door is unset. Core's own
 * default (`SystemWallClock`) is internal to core and cannot be threaded from this module;
 * `kotlin.time.Clock` has been stable stdlib API since Kotlin 2.3.
 */
internal val DrainSystemWallClock: WallClock =
    object : WallClock {
        override fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
    }
