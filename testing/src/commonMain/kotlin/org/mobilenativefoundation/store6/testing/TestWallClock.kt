package org.mobilenativefoundation.store6.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.WallClock
import kotlin.time.Duration

/** Controllable [WallClock]: time moves only when a test moves it. */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class TestWallClock(startEpochMillis: Long = 0L) : WallClock {
    private val now = MutableStateFlow(startEpochMillis)

    override fun nowEpochMillis(): Long = now.value

    /** Moves the clock forward by [duration]. */
    public fun advanceBy(duration: Duration) {
        now.update { it + duration.inWholeMilliseconds }
    }

    /** Sets the clock to an absolute epoch-milliseconds instant. */
    public fun setEpochMillis(epochMillis: Long) {
        now.value = epochMillis
    }
}
