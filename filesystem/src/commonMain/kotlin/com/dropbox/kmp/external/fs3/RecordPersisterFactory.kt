package com.dropbox.kmp.external.fs3

import com.dropbox.kmp.external.fs3.filesystem.FileSystem
import com.dropbox.kmp.external.fs3.filesystem.FileSystemFactory
import okio.BufferedSource
import okio.IOException
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * Factory for [RecordPersister]
 */
@ExperimentalTime
object RecordPersisterFactory {

    /**
     * Returns a new [BufferedSource] persister with the provided file as the root of the
     * persistence [FileSystem].
     *
     * @throws IOException
     */
    @Throws(IOException::class)
    fun create(
        root: String,
        expirationDuration: Duration
    ): Persister<BufferedSource, Pair<String, String>> =
        RecordPersister(FileSystemFactory.create(root), expirationDuration)

    /**
     * Returns a new [BufferedSource] persister with the provided fileSystem as the root of the
     * persistence [FileSystem].
     */
    fun create(
        fileSystem: FileSystem,
        expirationDuration: Duration
    ): Persister<BufferedSource, Pair<String, String>> =
        RecordPersister(fileSystem, expirationDuration)
}
