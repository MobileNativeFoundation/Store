package com.dropbox.android.external.fs3

import com.dropbox.android.external.fs3.filesystem.FileSystem
import com.dropbox.android.external.store4.legacy.BarCode
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalTime
class RecordPersister @Inject
constructor(
    fileSystem: FileSystem,
    private val expirationDuration: Duration
) : SourcePersister(fileSystem), RecordProvider<BarCode> {

    override fun getRecordState(key: BarCode): RecordState {
        return sourceFileReader.getRecordState(key, expirationDuration)
    }

    companion object {

        fun create(
            fileSystem: FileSystem,
            expirationDuration: Duration
        ): RecordPersister {
            return RecordPersister(fileSystem, expirationDuration)
        }
    }
}
