package com.dropbox.android.external.fs3

import com.dropbox.android.external.fs3.filesystem.FileSystem
import com.dropbox.android.external.store4.DiskRead
import okio.BufferedSource
import java.io.FileNotFoundException

/**
 * FSReader is used when persisting from file system
 * PathResolver will be used in creating file system paths based on cache keys.
 * Make sure to have keys containing same data resolve to same "path"
 *
 * @param <T> key type
</T> */
open class FSReader<Key>(
    internal val fileSystem: FileSystem,
    private val pathResolver: PathResolver<Key>
) : DiskRead<BufferedSource, Key> {

    override suspend fun read(key: Key): BufferedSource? {
        val resolvedKey = pathResolver.resolve(key)
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
