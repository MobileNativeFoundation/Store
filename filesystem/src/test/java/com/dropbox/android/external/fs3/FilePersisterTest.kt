package com.dropbox.android.external.fs3

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import com.dropbox.android.external.fs3.filesystem.FileSystem
import com.dropbox.android.external.store4.legacy.BarCode
import java.io.FileNotFoundException
import org.junit.Assert.fail
import kotlinx.coroutines.runBlocking
import okio.BufferedSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.Mockito.inOrder

class FilePersisterTest {
    private val fileSystem: FileSystem = mock()
    private val bufferedSource: BufferedSource = mock()
    private val simple = BarCode("type", "key")
    private val resolvedPath = BarCodePathResolver.resolve(simple)
    private val fileSystemPersister = FileSystemPersister.create(fileSystem, BarCodePathResolver)

    @Test
    fun readExists() = runBlocking {
        whenever(fileSystem.exists(resolvedPath))
                .thenReturn(true)
        whenever(fileSystem.read(resolvedPath)).thenReturn(bufferedSource)

        val returnedValue = fileSystemPersister.read(simple)
        assertThat(returnedValue).isEqualTo(bufferedSource)
    }

    @Test
    fun readDoesNotExist() = runBlocking {
        whenever(fileSystem.exists(resolvedPath))
                .thenReturn(false)

        try {
            fileSystemPersister.read(simple)
            fail()
        } catch (e: FileNotFoundException) {
        }
    }

    @Test
    fun writeThenRead() = runBlocking {
        whenever(fileSystem.read(resolvedPath)).thenReturn(bufferedSource)
        whenever(fileSystem.exists(resolvedPath)).thenReturn(true)
        fileSystemPersister.write(simple, bufferedSource)
        val source = fileSystemPersister.read(simple)
        val inOrder = inOrder(fileSystem)
        inOrder.verify<FileSystem>(fileSystem).write(resolvedPath, bufferedSource)
        inOrder.verify<FileSystem>(fileSystem).exists(resolvedPath)
        inOrder.verify<FileSystem>(fileSystem).read(resolvedPath)

        assertThat(source).isEqualTo(bufferedSource)
    }
}
