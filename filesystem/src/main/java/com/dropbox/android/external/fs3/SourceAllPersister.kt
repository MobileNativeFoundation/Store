package com.dropbox.android.external.fs3

import com.dropbox.android.external.fs3.filesystem.FileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import okio.BufferedSource
import java.io.FileNotFoundException

@ExperimentalCoroutinesApi
class SourceAllPersister(fileSystem: FileSystem) : AllPersister<BufferedSource, Pair<String, String>> {

    internal val sourceFileAllReader: FSAllReader = FSAllReader(fileSystem)
    internal val sourceFileAllEraser: FSAllEraser = FSAllEraser(fileSystem)

    internal val sourceFileReader: FSReader<Pair<String, String>> =
        FSReader(fileSystem, StringPairReadAllPathResolver)

    internal val sourceFileWriter: FSWriter<Pair<String, String>> =
        FSWriter(fileSystem, StringPairReadAllPathResolver)

    @Throws(FileNotFoundException::class)
    override fun CoroutineScope.readAll(path: String): ReceiveChannel<BufferedSource> {
        return with(sourceFileAllReader) {
            readAll(path)
        }
    }

    override suspend fun deleteAll(path: String): Boolean {
        return sourceFileAllEraser.deleteAll(path)
    }

    override suspend fun read(key: Pair<String, String>): BufferedSource? {
        return sourceFileReader.read(key)
    }

    override suspend fun write(key: Pair<String, String>, raw: BufferedSource): Boolean {
        return sourceFileWriter.write(key, raw)
    }

    companion object {

        fun create(fileSystem: FileSystem): SourceAllPersister {
            return SourceAllPersister(fileSystem)
        }
    }
}
