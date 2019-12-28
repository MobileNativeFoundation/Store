package com.dropbox.android.external.cache4

class TestClock(
    var virtualTimeNanos: Long = -1
) : Clock {
    override val currentTimeNanos: Long
        get() = virtualTimeNanos
}
