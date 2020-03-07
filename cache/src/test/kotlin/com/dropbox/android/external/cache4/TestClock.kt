package com.dropbox.android.external.cache4

import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalTime
class TestClock(
    @Volatile var virtualDuration: Duration = Duration.INFINITE
) : Clock {
    override val currentTimeNanos: Long
        get() = virtualDuration.toLongNanoseconds()
}
