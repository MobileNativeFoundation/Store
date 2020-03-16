package com.dropbox.android.external.fs3

import com.dropbox.android.external.fs3.filesystem.FileSystem
import com.dropbox.android.external.fs3.filesystem.FileSystemFactory
import com.dropbox.android.external.store4.Persister
import com.dropbox.android.external.store4.legacy.BarCode
import okio.BufferedSource
import java.io.File
import java.io.IOException
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
        root: File,
        expirationDuration: Duration
    ): Persister<BufferedSource, BarCode> =
            RecordPersister(FileSystemFactory.create(root), expirationDuration)

    /**
     * Returns a new [BufferedSource] persister with the provided fileSystem as the root of the
     * persistence [FileSystem].
     */
    fun create(
        fileSystem: FileSystem,
        expirationDuration: Duration
    ): Persister<BufferedSource, BarCode> =
            RecordPersister(fileSystem, expirationDuration)
}
