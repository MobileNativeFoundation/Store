package org.mobilenativefoundation.store6.sqldelight.internal

// This adapter has no Wasm-JS driver lane, and the current Wasm-JS runtime is single-threaded.
internal actual fun currentExecutionThreadId(): Long = 0L
