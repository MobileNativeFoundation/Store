package com.dropbox.kmp.external.fs3

import com.dropbox.kmp.external.fs3.filesystem.FileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.BufferedSource

class FSEraser<T>(
    internal val fileSystem: FileSystem,
    internal val pathResolver: (T) -> String
) : DiskErase<BufferedSource, T> {

    override suspend fun delete(key: T): Boolean {
        return withContext(Dispatchers.Default) { // (Dispatchers.IO) {
            fileSystem.delete(pathResolver(key))
            true
        }
    }
}
