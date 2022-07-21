package com.dropbox.kmp.external.fs3.filesystem

import okio.IOException

typealias RealFileSystem = okio.FileSystem

/**
 * Factory for [FileSystem].
 */
object FileSystemFactory {
    /**
     * Creates new instance of [FileSystemImpl].
     *
     * @param root root directory.
     * @return new instance of [FileSystemImpl].
     * @throws IOException
     */
    @Throws(IOException::class)
    fun create(root: String, realFileSystem: RealFileSystem): FileSystem =
        FileSystemImpl(root, realFileSystem)
}
