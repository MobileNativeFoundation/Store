package com.dropbox.kmp.external.fs3

import com.dropbox.kmp.external.fs3.filesystem.FileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.BufferedSource

/**
 * FSReader is used when persisting to file system
 * PathResolver will be used in creating file system paths based on cache keys.
 * Make sure to have keys containing same data resolve to same "path"
 * @param <T> key type
</T> */
open class FSWriter<Key>(
    internal val fileSystem: FileSystem,
    private val pathResolver: (Key) -> String
) : DiskWrite<BufferedSource, Key> {
    override suspend fun write(key: Key, raw: BufferedSource): Boolean {
        return withContext(Dispatchers.Default) { // withContext(Dispatchers.IO) {
            fileSystem.write(pathResolver(key), raw)
            true
        }
    }
}
