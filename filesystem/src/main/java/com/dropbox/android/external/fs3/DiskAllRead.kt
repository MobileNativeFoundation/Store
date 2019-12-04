package com.dropbox.android.external.fs3

import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel

interface DiskAllRead<Raw> {
    @Throws(FileNotFoundException::class)
    suspend fun CoroutineScope.readAll(path: String): ReceiveChannel<Raw>
}
