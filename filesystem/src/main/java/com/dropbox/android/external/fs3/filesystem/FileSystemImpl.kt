package com.dropbox.android.external.fs3.filesystem

import com.dropbox.android.external.cache4.Cache
import com.dropbox.android.external.fs3.RecordState
import com.dropbox.android.external.fs3.Util
import okio.BufferedSource
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.ArrayList
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * implements a [FileSystem] as regular files on disk in a specific document root (kind of like a root jail)
 *
 *
 * All operations are on the caller's thread.
 */
internal class FileSystemImpl(private val root: File) : FileSystem {

    private val files: Cache<String, FSFile> = Cache.Builder.newBuilder()
        .maximumCacheSize(20)
        .build()

    init {
        Util.createParentDirs(root)
    }

    @Throws(FileNotFoundException::class)
    override fun read(path: String): BufferedSource {
        return getFile(path)!!.source()
    }

    @Throws(IOException::class)
    override fun write(path: String, source: BufferedSource) {
        getFile(path)!!.write(source)
    }

    @Throws(IOException::class)
    override fun delete(path: String) {
        getFile(path)!!.delete()
    }

    @Throws(FileNotFoundException::class)
    override fun list(path: String): Collection<String> {
        val foundFiles = findFiles(path)
        val names = ArrayList<String>(foundFiles.size)
        for (foundFile in foundFiles) {
            names.add(foundFile.path())
        }
        return names
    }

    @Throws(FileNotFoundException::class)
    override fun deleteAll(path: String) {
        val foundFiles = findFiles(path)
        for (foundFile in foundFiles) {
            foundFile.delete()
        }
    }

    override fun exists(file: String): Boolean {
        return getFile(file)!!.exists()
    }

    @ExperimentalTime
    override fun getRecordState(
        expirationDuration: Duration,
        path: String
    ): RecordState {
        val file = getFile(path)
        if (!file!!.exists()) {
            return RecordState.MISSING
        }
        val now = System.currentTimeMillis()
        val cuttOffPoint = now - expirationDuration.toLongMilliseconds()
        return if (file.lastModified() < cuttOffPoint) {
            RecordState.STALE
        } else {
            RecordState.FRESH
        }
    }

    private fun getFile(path: String): FSFile? {
        val cleanedPath = cleanPath(path)
        return files.get(cleanedPath, loader = { FSFile(root, cleanedPath) })
    }

    private fun cleanPath(dirty: String): String =
        Util.simplifyPath(dirty)

    @Throws(FileNotFoundException::class)
    private fun findFiles(path: String): Collection<FSFile> {
        val searchRoot = File(root, Util.simplifyPath(path))
        if (searchRoot.exists() && searchRoot.isFile) {
            throw FileNotFoundException("expecting a directory at $path, instead found a file")
        }

        val foundFiles = ArrayList<FSFile>()
        val iterator = BreadthFirstFileTreeIterator(searchRoot)
        while (iterator.hasNext()) {
            val file = iterator.next() as File?
            val simplifiedPath = Util.simplifyPath(
                file!!.path.replaceFirst(root.path.toRegex(), "")
            )
            foundFiles.add(
                files.get(simplifiedPath, loader = { FSFile(root, simplifiedPath) })
            )
        }
        return foundFiles
    }
}
