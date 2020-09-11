package com.dropbox.android.external.fs3

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import com.dropbox.android.external.fs3.filesystem.FileSystem
import java.io.FileNotFoundException
import org.junit.Assert.fail
import kotlinx.coroutines.runBlocking
import okio.BufferedSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.ExperimentalTime
import kotlin.time.days

@ExperimentalTime
class RecordPersisterTest {

    private val fileSystem: FileSystem = mock()
    private val bufferedSource: BufferedSource = mock()

    private val sourcePersister = RecordPersister(fileSystem, 1.days)
    private val simple = "type" to "key"

    @Test
    fun readExists() = runBlocking<Unit> {
        whenever(fileSystem.exists(simple.toString()))
                .thenReturn(true)
        whenever(fileSystem.read(simple.toString())).thenReturn(bufferedSource)

        val returnedValue = sourcePersister.read(simple)
        assertThat(returnedValue).isEqualTo(bufferedSource)
    }

    @Test
    fun freshTest() = runBlocking {
        whenever(fileSystem.getRecordState(1.days, SourcePersister.pathForBarcode(simple)))
                .thenReturn(RecordState.FRESH)

        assertThat(sourcePersister.getRecordState(simple)).isEqualTo(RecordState.FRESH)
    }

    @Test
    fun staleTest() = runBlocking {
        whenever(fileSystem.getRecordState(1.days, SourcePersister.pathForBarcode(simple)))
                .thenReturn(RecordState.STALE)

        assertThat(sourcePersister.getRecordState(simple)).isEqualTo(RecordState.STALE)
    }

    @Test
    fun missingTest() = runBlocking {
        whenever(fileSystem.getRecordState(1.days, SourcePersister.pathForBarcode(simple)))
                .thenReturn(RecordState.MISSING)

        assertThat(sourcePersister.getRecordState(simple)).isEqualTo(RecordState.MISSING)
    }

    @Test
    fun readDoesNotExist() = runBlocking {
        whenever(fileSystem.exists(SourcePersister.pathForBarcode(simple)))
                .thenReturn(false)

        try {
            sourcePersister.read(simple)
            fail()
        } catch (e: FileNotFoundException) {
        }
    }

    @Test
    fun write() = runBlocking {
        assertThat(sourcePersister.write(simple, bufferedSource)).isTrue()
    }

    @Test
    fun pathForBarcode() = runBlocking {
        assertThat(SourcePersister.pathForBarcode(simple)).isEqualTo("typekey")
    }
}
