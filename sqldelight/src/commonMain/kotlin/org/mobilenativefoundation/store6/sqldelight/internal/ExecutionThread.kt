package org.mobilenativefoundation.store6.sqldelight.internal

/** Stable identity of the thread or worker currently executing adapter code. */
internal expect fun currentExecutionThreadId(): Long
