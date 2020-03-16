package com.dropbox.android.external.fs3

import com.dropbox.android.external.fs3.filesystem.FileSystem
import com.dropbox.android.external.store4.Persister
import okio.BufferedSource
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * FileSystemRecordPersister is used when persisting to/from file system while being stale aware
 * PathResolver will be used in creating file system paths based on cache keys.
 * Make sure to have keys containing same data resolve to same "path"
 *
 * @param <Key> key type
</Key> */
@ExperimentalTime
class FileSystemRecordPersister<Key> private constructor(
    private val fileSystem: FileSystem,
    private val pathResolver: PathResolver<Key>,
    private val expirationDuration: Duration
) : Persister<BufferedSource, Key>, RecordProvider<Key> {
    private val fileReader: FSReader<Key> = FSReader(fileSystem, pathResolver)
    private val fileWriter: FSWriter<Key> = FSWriter(fileSystem, pathResolver)

    override fun getRecordState(key: Key): RecordState =
        fileSystem.getRecordState(expirationDuration, pathResolver.resolve(key))

    override suspend fun read(key: Key): BufferedSource? = fileReader.read(key)

    override suspend fun write(key: Key, raw: BufferedSource): Boolean = fileWriter.write(key, raw)

    companion object {

        fun <T> create(
            fileSystem: FileSystem,
            pathResolver: PathResolver<T>,
            expirationDuration: Duration
        ): FileSystemRecordPersister<T> = FileSystemRecordPersister(
            fileSystem = fileSystem,
            pathResolver = pathResolver,
            expirationDuration = expirationDuration
        )
    }
}
