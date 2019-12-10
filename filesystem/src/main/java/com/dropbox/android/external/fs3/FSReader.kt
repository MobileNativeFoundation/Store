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
open class FSReader<T>(internal val fileSystem: FileSystem, internal val pathResolver: PathResolver<T>) : DiskRead<BufferedSource, T> {

    override suspend fun read(key: T): BufferedSource? {
        val resolvedKey = pathResolver.resolve(key)
        val exists = fileSystem.exists(resolvedKey)
        if (exists == true) {
            var bufferedSource: BufferedSource? = null
            try {
                bufferedSource = fileSystem.read(resolvedKey)
                return bufferedSource
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
        private val ERROR_MESSAGE = "resolvedKey does not resolve to a file"
    }
}
