package com.dropbox.kmp.external.fs3

import com.dropbox.kmp.external.fs3.filesystem.FileSystemFactory
import okio.FileSystem

@Throws(Exception::class)
actual fun FileSystemFactory.create(root: String) = FileSystemFactory.create(root, FileSystem.SYSTEM)
