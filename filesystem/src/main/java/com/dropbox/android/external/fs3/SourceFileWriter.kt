package com.dropbox.android.external.fs3

import com.dropbox.android.external.fs3.filesystem.FileSystem
import com.dropbox.android.external.store4.DiskWrite
import okio.BufferedSource

class SourceFileWriter @JvmOverloads constructor(
    fileSystem: FileSystem,
    pathResolver: PathResolver<Pair<String, String>> = StringPairPathResolver
) : FSWriter<Pair<String, String>>(fileSystem, pathResolver), DiskWrite<BufferedSource, Pair<String, String>>
