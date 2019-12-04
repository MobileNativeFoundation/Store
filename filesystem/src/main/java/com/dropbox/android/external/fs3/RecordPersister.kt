package com.dropbox.android.external.fs3

import com.dropbox.android.external.fs3.filesystem.FileSystem
import com.dropbox.android.external.store4.legacy.BarCode
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class RecordPersister @Inject
constructor(
    fileSystem: FileSystem,
    private val expirationDuration: Long,
    private val expirationUnit: TimeUnit
) : SourcePersister(fileSystem), RecordProvider<BarCode> {

    override fun getRecordState(key: BarCode): RecordState {
        return sourceFileReader.getRecordState(key, expirationUnit, expirationDuration)
    }

    companion object {

        fun create(
            fileSystem: FileSystem,
            expirationDuration: Long,
            expirationUnit: TimeUnit
        ): RecordPersister {
            return RecordPersister(fileSystem, expirationDuration, expirationUnit)
        }
    }
}
