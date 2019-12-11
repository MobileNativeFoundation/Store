package com.dropbox.android.external.fs3

import com.dropbox.android.external.fs3.filesystem.FileSystem
import com.dropbox.android.external.store4.Persister
import okio.BufferedSource

/**
 * FileSystemPersister is used when persisting to/from file system
 * PathResolver will be used in creating file system paths based on cache keys.
 * Make sure to have keys containing same data resolve to same "path"
 * @param <T> key type
</T> */
class FileSystemPersister<T> private constructor(fileSystem: FileSystem, pathResolver: PathResolver<T>) : Persister<BufferedSource, T> {
    private val fileReader: FSReader<T> = FSReader(fileSystem, pathResolver)
    private val fileWriter: FSWriter<T> = FSWriter(fileSystem, pathResolver)

    override suspend fun read(key: T): BufferedSource? =
            fileReader.read(key)

    override suspend fun write(key: T, raw: BufferedSource): Boolean =
            fileWriter.write(key, raw)

    companion object {

        fun <T> create(
            fileSystem: FileSystem,
            pathResolver: PathResolver<T>
        ): Persister<BufferedSource, T> =
                FileSystemPersister(fileSystem, pathResolver)
    }
}
