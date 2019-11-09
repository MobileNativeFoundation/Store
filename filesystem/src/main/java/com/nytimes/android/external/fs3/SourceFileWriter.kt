package com.nytimes.android.external.fs3

import com.nytimes.android.external.fs3.filesystem.FileSystem
import com.nytimes.android.external.store4.DiskWrite
import com.nytimes.android.external.store4.legacy.BarCode
import okio.BufferedSource

class SourceFileWriter @JvmOverloads constructor(
    fileSystem: FileSystem,
    pathResolver: PathResolver<BarCode> = BarCodePathResolver()
) : FSWriter<BarCode>(fileSystem, pathResolver), DiskWrite<BufferedSource, BarCode>
