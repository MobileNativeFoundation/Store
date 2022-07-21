package com.dropbox.android.external.fs3

import com.dropbox.android.external.fs3.SourcePersister.Companion.pathForBarcode
import com.dropbox.android.external.fs3.filesystem.FileSystem
import okio.BufferedSource
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

class SourceFileReader @JvmOverloads constructor(
    fileSystem: FileSystem,
    pathResolver: PathResolver<Pair<String, String>> = StringPairPathResolver
) : FSReader<Pair<String, String>>(fileSystem, pathResolver), DiskRead<BufferedSource, Pair<String, String>> {

    @ExperimentalTime
    fun getRecordState(
        barCode: Pair<String, String>,
        expirationDuration: Duration
    ): RecordState {
        return fileSystem.getRecordState(expirationDuration, pathForBarcode(barCode))
    }
}
