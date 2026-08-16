package org.mobilenativefoundation.store6.sqldelight.internal

// This adapter has no JavaScript driver lane, and the current JS runtime is single-threaded.
internal actual fun currentExecutionThreadId(): Long = 0L
