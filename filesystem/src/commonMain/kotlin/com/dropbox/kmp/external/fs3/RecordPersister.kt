package com.dropbox.android.external.fs3

import com.dropbox.android.external.fs3.filesystem.FileSystem
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@ExperimentalTime
class RecordPersister(
    fileSystem: FileSystem,
    private val expirationDuration: Duration
) : SourcePersister(fileSystem), RecordProvider<Pair<String, String>> {

    override fun getRecordState(key: Pair<String, String>): RecordState {
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
