package com.dropbox.android.external.fs3

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.FileNotFoundException

interface DiskAllRead<Raw> {
    @Throws(FileNotFoundException::class)
    fun CoroutineScope.readAll(path: String): ReceiveChannel<Raw>
}
