package com.dropbox.kmp.external.fs3

import com.dropbox.kmp.external.fs3.filesystem.RealFileSystem
import kotlinx.datetime.Clock
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

internal object Util {
    @Throws(IOException::class)
    fun createParentDirs(realFileSystem: RealFileSystem, file: Path) {
        val parent = file.parent /*
       * The given directory is a filesystem root. All zero of its ancestors
       * exist. This doesn't mean that the root itself exists -- consider x:\ on
       * a Windows machine without such a drive -- or even that the caller can
       * create it, but this method makes no such guarantees even for non-root
       * files.
       */
            ?: return
        realFileSystem.createDirectory(parent)
        if (realFileSystem.metadataOrNull(parent)?.isDirectory != true) {
            throw IOException("Unable to create parent directories of $file")
        }
    }

    fun createTempFile(realFileSystem: RealFileSystem, prefix: String, suffix: String, directory: Path): Path {
        var i = 0
        while (true) {
            val file = (directory + "$prefix${Clock.System.now().toEpochMilliseconds()}-$i.$suffix")
            if (!realFileSystem.exists(file)) {
                realFileSystem.sink(file, true).close()
                return file
            }
            i++
        }
    }
}

operator fun Path.plus(other: String) = this.toString().let {
    if (it.endsWith(Path.DIRECTORY_SEPARATOR) || other.startsWith(Path.DIRECTORY_SEPARATOR)) {
        "$it$other"
    } else {
        "$it${Path.DIRECTORY_SEPARATOR}$other"
    }
}.toPath()
