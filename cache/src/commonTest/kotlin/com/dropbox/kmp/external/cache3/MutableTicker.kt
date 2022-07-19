package com.dropbox.kmp.external.cache3

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

class MutableTicker(private var duration: Duration = 0.nanoseconds) {
    val ticker: Ticker = { duration.inWholeNanoseconds }

    operator fun plusAssign(duration: Duration) {
        this.duration += duration
    }
}
