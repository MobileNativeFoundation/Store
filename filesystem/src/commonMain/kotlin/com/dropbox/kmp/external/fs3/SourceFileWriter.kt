package com.dropbox.kmp.external.fs3

import com.dropbox.kmp.external.fs3.filesystem.FileSystem
import okio.BufferedSource

class SourceFileWriter(
    fileSystem: FileSystem,
    pathResolver: (Pair<String, String>) -> String = StringPairPathResolver
) : FSWriter<Pair<String, String>>(fileSystem, pathResolver), DiskWrite<BufferedSource, Pair<String, String>>
