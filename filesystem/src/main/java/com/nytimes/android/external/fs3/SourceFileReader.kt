package com.nytimes.android.external.fs3

import com.nytimes.android.external.fs3.SourcePersister.Companion.pathForBarcode
import com.nytimes.android.external.fs3.filesystem.FileSystem
import com.nytimes.android.external.store4.DiskRead
import com.nytimes.android.external.store4.legacy.BarCode

import java.util.concurrent.TimeUnit

import okio.BufferedSource


class SourceFileReader @JvmOverloads constructor(fileSystem: FileSystem, pathResolver: PathResolver<BarCode> = BarCodePathResolver())
    : FSReader<BarCode>(fileSystem, pathResolver), DiskRead<BufferedSource, BarCode> {

    fun getRecordState(barCode: BarCode,
                       expirationUnit: TimeUnit,
                       expirationDuration: Long): RecordState {
        return fileSystem.getRecordState(expirationUnit, expirationDuration, pathForBarcode(barCode))
    }
}
