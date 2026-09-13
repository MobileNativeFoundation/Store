package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.WallClock

/** The production clock backed by each platform's system clock. */
@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
internal val SystemWallClock: WallClock =
    object : WallClock {
        override fun nowEpochMillis(): Long = currentEpochMillis()
    }

/** Reads the platform's wall clock in milliseconds since the Unix epoch. */
internal expect fun currentEpochMillis(): Long
