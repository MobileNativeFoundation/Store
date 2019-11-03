package com.nytimes.android.external.fs3


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.FileNotFoundException

interface DiskAllRead<Raw> {
    @Throws(FileNotFoundException::class)
    suspend fun CoroutineScope.readAll(path: String): ReceiveChannel<Raw>
}

