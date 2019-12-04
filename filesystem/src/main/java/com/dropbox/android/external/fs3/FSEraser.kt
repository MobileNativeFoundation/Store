package com.dropbox.android.external.fs3


import com.dropbox.android.external.fs3.filesystem.FileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.BufferedSource

class FSEraser<T>(
        internal val fileSystem: FileSystem,
        internal val pathResolver: PathResolver<T>
) : DiskErase<BufferedSource, T> {

    override suspend fun delete(key: T): Boolean {
        return withContext(Dispatchers.IO) {
            fileSystem.delete(pathResolver.resolve(key))
            true
        }
    }
}
