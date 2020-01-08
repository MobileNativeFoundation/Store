package com.dropbox.android.external.cache4

/**
 * A time source used for calculating cache expiration.
 */
interface Clock {
    val currentTimeNanos: Long
}

/**
 * A [Clock] that reports the current system time.
 */
internal object SystemClock : Clock {
    override val currentTimeNanos: Long
        get() = System.nanoTime()
}
