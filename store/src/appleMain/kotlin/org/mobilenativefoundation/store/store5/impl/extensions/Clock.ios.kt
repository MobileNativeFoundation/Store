package org.mobilenativefoundation.store.store5.impl.extensions

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentTimeMillis(): Long =
    memScoped {
        val tv = alloc<timeval>()
        gettimeofday(tv.ptr, null)
        (tv.tv_sec.toLong() * 1000L) + (tv.tv_usec.toLong() / 1000L)
    }
