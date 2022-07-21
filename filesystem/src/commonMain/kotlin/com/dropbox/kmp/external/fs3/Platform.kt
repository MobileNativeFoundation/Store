package com.dropbox.kmp.external.fs3

import com.dropbox.kmp.external.fs3.filesystem.FileSystem
import com.dropbox.kmp.external.fs3.filesystem.FileSystemFactory
import okio.IOException

@Throws(IOException::class)
expect fun FileSystemFactory.create(root: String): FileSystem
