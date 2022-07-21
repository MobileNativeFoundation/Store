package com.dropbox.kmp.external.fs3

import com.dropbox.kmp.external.fs3.filesystem.FileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.IOException
import kotlin.coroutines.cancellation.CancellationException

class FSAllEraser(internal val fileSystem: FileSystem) : DiskAllErase {
    @Throws(IOException::class, CancellationException::class)
    override suspend fun deleteAll(path: String): Boolean {
        return withContext(Dispatchers.Default) { // withContext(Dispatchers.IO) {
            fileSystem.deleteAll(path)
            true
        }
    }
}
