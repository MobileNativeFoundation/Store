package org.mobilenativefoundation.store6.sqldelight.internal

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/** Returns the Apple system wall clock in Unix epoch milliseconds. */
internal actual fun currentEpochMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1_000.0).toLong()
