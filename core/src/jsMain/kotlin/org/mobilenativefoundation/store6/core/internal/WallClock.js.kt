package org.mobilenativefoundation.store6.core.internal

import kotlin.js.Date

/** Returns the JavaScript system wall clock in Unix epoch milliseconds. */
internal actual fun currentEpochMillis(): Long = Date.now().toLong()
