package com.dropbox.kmp.external.fs3

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import okio.FileNotFoundException

interface DiskAllRead<Raw> {
    @Throws(FileNotFoundException::class)
    fun CoroutineScope.readAll(path: String): ReceiveChannel<Raw>
}
