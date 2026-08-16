package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal suspend fun awaitUntil(
    timeout: Duration = 10.seconds,
    condition: suspend () -> Boolean,
) {
    withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        while (!condition()) {
            check(started.elapsedNow() < timeout) {
                "Condition was not satisfied within $timeout."
            }
            delay(20)
        }
    }
}
