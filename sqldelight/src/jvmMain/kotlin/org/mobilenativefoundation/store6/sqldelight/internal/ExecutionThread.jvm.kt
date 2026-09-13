package org.mobilenativefoundation.store6.sqldelight.internal

@Suppress("DEPRECATION")
internal actual fun currentExecutionThreadId(): Long = Thread.currentThread().id
