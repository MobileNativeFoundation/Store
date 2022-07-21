package com.dropbox.kmp.external.fs3.filesystem

import com.dropbox.kmp.external.fs3.Util
import com.dropbox.kmp.external.fs3.plus
import okio.BufferedSource
import okio.FileNotFoundException
import okio.IOException
import okio.Path
import okio.buffer
import okio.use

internal class FSFile(private val realFileSystem: RealFileSystem, root: Path, private val pathValue: String) {
    private val file = root + pathValue

    init {
        if (realFileSystem.exists(file) && realFileSystem.metadataOrNull(file)?.isRegularFile != true) {
            throw FileNotFoundException("expecting a file at $pathValue, instead found a directory")
        }
        Util.createParentDirs(realFileSystem, file)
    }

    fun exists(): Boolean = realFileSystem.exists(file)

    fun delete() {
        /**
         * it's ok to delete the file even if we still have readers! the file won't really
         * be deleted until all readers close it (it just removes the name-to-inode mapping)
         */
        realFileSystem.delete(file, false)
    }

    fun path(): String = pathValue

    @Throws(IOException::class)
    fun write(source: BufferedSource) {
        val tmpFile: Path = Util.createTempFile(realFileSystem, "new", "tmp", file.parent!!)
        try {
            realFileSystem.sink(tmpFile).buffer().use { sink ->
                source.use { sink.writeAll(it) }
            }
        } catch (e: Exception) {
            throw IOException("unable to write to file", e)
        }

        try {
            realFileSystem.atomicMove(tmpFile, file)
        } finally {
            realFileSystem.delete(tmpFile, false)
        }
    }

    @Throws(FileNotFoundException::class)
    fun source(): BufferedSource {
        if (realFileSystem.exists(file)) {
            return realFileSystem.source(file).buffer()
        }
        throw FileNotFoundException(pathValue)
    }

    fun lastModified(): Long = realFileSystem.metadataOrNull(file)?.lastModifiedAtMillis ?: 0
}
