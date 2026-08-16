package org.mobilenativefoundation.store6.core.internal

/** Returns the JVM system wall clock in Unix epoch milliseconds. */
internal actual fun currentEpochMillis(): Long = System.currentTimeMillis()
