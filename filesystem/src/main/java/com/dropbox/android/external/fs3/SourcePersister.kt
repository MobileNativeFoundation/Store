package com.dropbox.android.external.fs3

import com.dropbox.android.external.fs3.filesystem.FileSystem
import com.dropbox.android.external.store4.Persister
import com.dropbox.android.external.store4.legacy.BarCode
import okio.BufferedSource

/**
 * Persister to be used when storing something to persister from a BufferedSource
 * example usage:
 * ParsingStoreBuilder.<BufferedSource></BufferedSource>, BookResults>builder()
 * .fetcher(fetcher)
 * .persister(new SourcePersister(fileSystem))
 * .parser(new GsonSourceParser<>(gson, BookResults.class))
 * .open();
 */
open class SourcePersister(fileSystem: FileSystem) : Persister<BufferedSource, BarCode> {

    protected val sourceFileReader: SourceFileReader = SourceFileReader(fileSystem)
    protected val sourceFileWriter: SourceFileWriter = SourceFileWriter(fileSystem)

    override suspend fun read(key: BarCode): BufferedSource? {
        return sourceFileReader.read(key)
    }

    override suspend fun write(key: BarCode, raw: BufferedSource): Boolean {
        return sourceFileWriter.write(key, raw)
    }

    companion object {

        fun create(fileSystem: FileSystem): SourcePersister {
            return SourcePersister(fileSystem)
        }

        internal fun pathForBarcode(barCode: BarCode): String {
            return barCode.type + barCode.key
        }
    }
}
