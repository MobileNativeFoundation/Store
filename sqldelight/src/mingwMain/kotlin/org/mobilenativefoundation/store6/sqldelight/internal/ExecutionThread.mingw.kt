@file:OptIn(ObsoleteWorkersApi::class)

package org.mobilenativefoundation.store6.sqldelight.internal

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker

internal actual fun currentExecutionThreadId(): Long = Worker.current.id.toLong()
