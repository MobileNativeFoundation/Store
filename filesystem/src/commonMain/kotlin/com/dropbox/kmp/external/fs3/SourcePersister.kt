package com.dropbox.kmp.external.fs3

import com.dropbox.kmp.external.fs3.filesystem.FileSystem
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
open class SourcePersister(fileSystem: FileSystem) : Persister<BufferedSource, Pair<String, String>> {

    protected val sourceFileReader: SourceFileReader = SourceFileReader(fileSystem)
    protected val sourceFileWriter: SourceFileWriter = SourceFileWriter(fileSystem)

    override suspend fun read(key: Pair<String, String>): BufferedSource? {
        return sourceFileReader.read(key)
    }

    override suspend fun write(key: Pair<String, String>, raw: BufferedSource): Boolean {
        return sourceFileWriter.write(key, raw)
    }

    companion object {

        fun create(fileSystem: FileSystem): SourcePersister {
            return SourcePersister(fileSystem)
        }

        internal fun pathForBarcode(barCode: Pair<String, String>): String {
            return barCode.first + barCode.second
        }
    }
}
