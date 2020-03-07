package com.dropbox.android.external.fs3

import com.dropbox.android.external.fs3.filesystem.FileSystem
import com.dropbox.android.external.fs3.filesystem.FileSystemFactory
import com.dropbox.android.external.store4.Persister
import com.dropbox.android.external.store4.legacy.BarCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okio.BufferedSource
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.ExperimentalTime

/**
 * Factory for [SourcePersister]
 */

@ExperimentalCoroutinesApi
object SourcePersisterFactory {

    /**
     * Returns a new [BufferedSource] persister with the provided file as the root of the
     * persistence [FileSystem].
     *
     * @throws IOException
     */
    @ExperimentalTime
    @Throws(IOException::class)
    fun create(
        root: File,
        expirationDuration: Long,
        expirationUnit: TimeUnit
    ): Persister<BufferedSource, BarCode> {
        return RecordPersister.create(FileSystemFactory.create(root), expirationDuration, expirationUnit)
    }

    /**
     * Returns a new [BufferedSource] persister with the provided fileSystem as the root of the
     * persistence [FileSystem].
     */
    fun create(
        fileSystem: FileSystem,
        expirationDuration: Long,
        expirationUnit: TimeUnit
    ): Persister<BufferedSource, BarCode> {
        return RecordPersister.create(fileSystem, expirationDuration, expirationUnit)
    }

    /**
     * Returns a new [BufferedSource] persister with the provided file as the root of the
     * persistence [FileSystem].
     *
     * @throws IOException
     */
    @ExperimentalTime
    @Throws(IOException::class)
    fun create(root: File): Persister<BufferedSource, BarCode> {
        return SourcePersister.create(FileSystemFactory.create(root))
    }

    /**
     * Returns a new [BufferedSource] persister with the provided fileSystem as the root of the
     * persistence [FileSystem].
     */
    fun create(fileSystem: FileSystem): Persister<BufferedSource, BarCode> {
        return SourcePersister.create(fileSystem)
    }

    /**
     * Returns a new [BufferedSource] persister with the provided file as the root of the
     * persistence [FileSystem].
     *
     * @throws IOException
     */
    @ExperimentalTime
    @Throws(IOException::class)
    fun createAll(root: File): Persister<BufferedSource, BarCode> {
        return SourceAllPersister.create(FileSystemFactory.create(root))
    }

    /**
     * Returns a new [BufferedSource] persister with the provided fileSystem as the root of the
     * persistence [FileSystem].
     */
    fun createAll(fileSystem: FileSystem): Persister<BufferedSource, BarCode> {
        return SourceAllPersister.create(fileSystem)
    }
}
