package com.dropbox.android.external.fs3

import com.dropbox.android.external.fs3.SourcePersister.Companion.pathForBarcode
import com.dropbox.android.external.fs3.filesystem.FileSystem
import com.dropbox.android.external.store4.DiskRead
import com.dropbox.android.external.store4.legacy.BarCode
import okio.BufferedSource
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

class SourceFileReader @JvmOverloads constructor(
    fileSystem: FileSystem,
    pathResolver: PathResolver<BarCode> = BarCodePathResolver
) : FSReader<BarCode>(fileSystem, pathResolver), DiskRead<BufferedSource, BarCode> {

    @ExperimentalTime
    fun getRecordState(
        barCode: BarCode,
        expirationDuration: Duration
    ): RecordState {
        return fileSystem.getRecordState(expirationDuration, pathForBarcode(barCode))
    }
}
