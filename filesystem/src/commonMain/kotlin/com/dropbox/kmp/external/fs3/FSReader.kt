package com.dropbox.kmp.external.fs3

import com.dropbox.kmp.external.fs3.filesystem.FileSystem
import okio.BufferedSource
import okio.FileNotFoundException

/**
 * FSReader is used when persisting from file system
 * PathResolver will be used in creating file system paths based on cache keys.
 * Make sure to have keys containing same data resolve to same "path"
 *
 * @param <T> key type
</T> */
open class FSReader<Key>(
    internal val fileSystem: FileSystem,
    private val pathResolver: (Key) -> String
) : DiskRead<BufferedSource, Key> {

    override suspend fun read(key: Key): BufferedSource? {
        val resolvedKey = pathResolver(key)
        val exists = fileSystem.exists(resolvedKey)

        if (exists) {
            return try {
                fileSystem.read(resolvedKey)
            } catch (e: FileNotFoundException) {
                throw e
            } finally {
                // TODO MIKE: figure out why this was here
//                if (bufferedSource != null) {
//                    try {
//                        bufferedSource.close()
//                    } catch (e: IOException) {
//                        e.printStackTrace(System.err)
//                    }
//                }
            }
        } else {
            throw FileNotFoundException(ERROR_MESSAGE + resolvedKey)
        }
    }

    companion object {
        private const val ERROR_MESSAGE = "resolvedKey does not resolve to a file"
    }
}
