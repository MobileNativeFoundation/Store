package com.dropbox.kmp.external.fs3.filesystem

import com.dropbox.kmp.external.cache3.Cache
import com.dropbox.kmp.external.cache3.cacheBuilder
import com.dropbox.kmp.external.fs3.RecordState
import com.dropbox.kmp.external.fs3.Util
import com.dropbox.kmp.external.fs3.plus
import kotlinx.datetime.Clock
import okio.BufferedSource
import okio.FileNotFoundException
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * implements a [FileSystem] as regular files on disk in a specific document root (kind of like a root jail)
 *
 *
 * All operations are on the caller's thread.
 */
internal class FileSystemImpl(_root: String, private val realFileSystem: RealFileSystem) : FileSystem {

    private val files: Cache<String, FSFile> = cacheBuilder { maximumSize(20) }
    private val root = _root.toPath()

    init {
        Util.createParentDirs(realFileSystem, root)
    }

    @Throws(FileNotFoundException::class)
    override fun read(path: String): BufferedSource {
        return getFile(path).source()
    }

    @Throws(IOException::class)
    override fun write(path: String, source: BufferedSource) {
        getFile(path).write(source)
    }

    @Throws(IOException::class)
    override fun delete(path: String) {
        getFile(path).delete()
    }

    @Throws(FileNotFoundException::class)
    override fun list(path: String): Collection<String> {
        return findFiles(path)
            .map { it.path() }
            .toList()
    }

    @Throws(IOException::class)
    override fun deleteAll(path: String) {
        findFiles(path)
            .forEach { it.delete() }
    }

    override fun exists(file: String): Boolean {
        return getFile(file).exists()
    }

    @ExperimentalTime
    override fun getRecordState(
        expirationDuration: Duration,
        path: String
    ): RecordState {
        val file = getFile(path)
        if (!file.exists()) {
            return RecordState.MISSING
        }
        val now = Clock.System.now().toEpochMilliseconds()
        val cuttOffPoint: Long = now - expirationDuration.inWholeMilliseconds
        return if (file.lastModified() < cuttOffPoint) {
            RecordState.STALE
        } else {
            RecordState.FRESH
        }
    }

    private fun getFile(path: String): FSFile {
        return path.toPath(true).toString()
            .let { files.getOrPut(it) { FSFile(realFileSystem, root, it) } }
    }

    @Throws(FileNotFoundException::class)
    private fun findFiles(path: String): Sequence<FSFile> {
        val searchRoot = root + path
        if (realFileSystem.exists(searchRoot) && realFileSystem.metadataOrNull(searchRoot)?.isDirectory != true) {
            throw FileNotFoundException("expecting a directory at $path, instead found a file")
        }
        val prefix = root.toString()
        return realFileSystem.listRecursively(searchRoot)
            .filter { realFileSystem.metadata(it).isRegularFile }
            .map {
                val simplifiedPath = it.normalized().toString().removePrefix(prefix)
                files.getOrPut(simplifiedPath) { FSFile(realFileSystem, root, simplifiedPath) }
            }
    }
}
