package com.dropbox.android.external.fs3.filesystem

import java.io.File
import java.io.IOException
import kotlin.time.ExperimentalTime

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
    @ExperimentalTime
    @Throws(IOException::class)
    fun create(root: File): FileSystem = FileSystemImpl(root)
}
