@file:OptIn(ExperimentalForeignApi::class)

package org.mobilenativefoundation.store6.core.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.FILETIME
import platform.windows.GetSystemTimeAsFileTime

/** Returns the Windows system wall clock in Unix epoch milliseconds. */
internal actual fun currentEpochMillis(): Long =
    memScoped {
        val fileTime = alloc<FILETIME>()
        GetSystemTimeAsFileTime(fileTime.ptr)
        val ticks =
            (fileTime.dwHighDateTime.toLong() shl 32) or
                fileTime.dwLowDateTime.toLong()
        (ticks / 10_000L) - 11_644_473_600_000L
    }
