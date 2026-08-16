package org.mobilenativefoundation.store6.core.internal

private fun jsDateNow(): Double = js("Date.now()")

/** Returns the Wasm-JS system wall clock in Unix epoch milliseconds. */
internal actual fun currentEpochMillis(): Long = jsDateNow().toLong()
