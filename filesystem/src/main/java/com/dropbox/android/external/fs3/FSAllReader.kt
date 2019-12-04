package com.dropbox.android.external.fs3

import com.dropbox.android.external.fs3.filesystem.FileSystem
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import okio.BufferedSource

/**
 * FSReader is used when persisting from file system
 * PathResolver will be used in creating file system paths based on cache keys.
 * Make sure to have keys containing same data resolve to same "path"
 *
 */
class FSAllReader(internal val fileSystem: FileSystem) : DiskAllRead<BufferedSource> {

    @Throws(FileNotFoundException::class)
    override suspend fun CoroutineScope.readAll(path: String): ReceiveChannel<BufferedSource> {
        return produce {
            fileSystem.list(path).forEach {
                send(fileSystem.read(it))
            }
        }
    }
}
