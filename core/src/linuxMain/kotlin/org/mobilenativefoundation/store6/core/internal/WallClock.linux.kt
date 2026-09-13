@file:OptIn(ExperimentalForeignApi::class)

package org.mobilenativefoundation.store6.core.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval

/** Returns the Linux system wall clock in Unix epoch milliseconds. */
internal actual fun currentEpochMillis(): Long =
    memScoped {
        val now = alloc<timeval>()
        gettimeofday(now.ptr, null)
        (now.tv_sec.toLong() * 1_000L) + (now.tv_usec.toLong() / 1_000L)
    }
