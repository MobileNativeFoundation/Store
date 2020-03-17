package com.dropbox.android.external.fs3

import com.dropbox.android.external.fs3.filesystem.FileSystem
import com.dropbox.android.external.store4.legacy.BarCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import okio.BufferedSource
import java.io.FileNotFoundException

@ExperimentalCoroutinesApi
class SourceAllPersister(fileSystem: FileSystem) : AllPersister<BufferedSource, BarCode> {

    internal val sourceFileAllReader: FSAllReader = FSAllReader(fileSystem)
    internal val sourceFileAllEraser: FSAllEraser = FSAllEraser(fileSystem)

    internal val sourceFileReader: FSReader<BarCode> =
        FSReader(fileSystem, BarCodeReadAllPathResolver)

    internal val sourceFileWriter: FSWriter<BarCode> =
        FSWriter(fileSystem, BarCodeReadAllPathResolver)

    @Throws(FileNotFoundException::class)
    override fun CoroutineScope.readAll(path: String): ReceiveChannel<BufferedSource> {
        return with(sourceFileAllReader) {
            readAll(path)
        }
    }

    override suspend fun deleteAll(path: String): Boolean {
        return sourceFileAllEraser.deleteAll(path)
    }

    override suspend fun read(key: BarCode): BufferedSource? {
        return sourceFileReader.read(key)
    }

    override suspend fun write(key: BarCode, raw: BufferedSource): Boolean {
        return sourceFileWriter.write(key, raw)
    }

    companion object {

        fun create(fileSystem: FileSystem): SourceAllPersister {
            return SourceAllPersister(fileSystem)
        }
    }
}
