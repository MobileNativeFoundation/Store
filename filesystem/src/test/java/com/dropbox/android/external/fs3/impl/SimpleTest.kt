package com.dropbox.android.external.fs3.impl

import com.dropbox.android.external.fs3.RecordState
import com.dropbox.android.external.fs3.filesystem.FileSystem
import com.dropbox.android.external.fs3.filesystem.FileSystemFactory
import com.google.common.base.Charsets.UTF_8
import com.google.common.io.Files.createTempDir
import com.google.common.truth.Truth.assertThat
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.time.days
import kotlin.time.microseconds
import kotlin.time.minutes

class SimpleTest {

    private lateinit var fileSystem: FileSystem

    @Before
    @Throws(IOException::class)
    fun start() {
        val baseDir = createTempDir()
        fileSystem = FileSystemFactory.create(baseDir)
    }

    @Test(expected = FileNotFoundException::class)
    @Throws(IOException::class)
    fun loadFileNotFound() {
        fileSystem.read("/loadFileNotFound.txt").readUtf8()
    }

    @Test
    @Throws(IOException::class)
    fun saveNload() {
        diffMe("/flibber.txt", "/flibber.txt")
        diffMe("/blarg/flibber.txt", "/blarg/flibber.txt")
        diffMe("/blubber.txt", "blubber.txt")
        diffMe("/blarg/blubber.txt", "blarg/blubber.txt")
    }

    @Test
    @Throws(IOException::class)
    fun delete() {
        fileSystem.write("/boo", source(testString1))
        assertThat(fileSystem.read("/boo").readUtf8()).isEqualTo(testString1)
        fileSystem.delete("/boo")
        assertThat(fileSystem.exists("/boo")).isFalse()
    }

    @Test
    @Throws(IOException::class)
    fun testIsRecordStale() {
        fileSystem.write("/boo", source(testString1))
        assertThat(fileSystem.read("/boo").readUtf8()).isEqualTo(testString1)
        assertThat(fileSystem.getRecordState(1.minutes, "/boo")).isEqualTo(RecordState.FRESH)
        assertThat(fileSystem.getRecordState(1.microseconds, "/boo")).isEqualTo(RecordState.STALE)
        assertThat(fileSystem.getRecordState(1.days, "/notfound")).isEqualTo(RecordState.MISSING)
    }

    @Test
    @Throws(IOException::class)
    fun testDeleteWhileReading() {

        fileSystem.write("/boo", source(testString1))
        val source = fileSystem.read("/boo")
        fileSystem.delete("/boo")

        assertThat(fileSystem.exists("/boo")).isFalse()
        assertThat(source.readUtf8()).isEqualTo(testString1)
        assertThat(fileSystem.exists("/boo")).isFalse()
    }

    @Test
    @Throws(IOException::class)
    fun deleteWhileReadingThenWrite() {

        fileSystem.write("/boo", source(testString1))

        val source1 = fileSystem.read("/boo") // open a source and hang onto it
        fileSystem.delete("/boo") // now delete the file

        assertThat(fileSystem.exists("/boo")).isFalse() // exists() should say it's gone even though
        // we still have a source to it
        fileSystem.write("/boo", source(testString2)) // and now un-delete it by writing a new version
        assertThat(fileSystem.exists("/boo")).isTrue() // exists() should say it's back
        val source2 = fileSystem.read("/boo") // open another source and hang onto it
        fileSystem.delete("/boo") // now delete the file *again*

        // the sources should have the correct data even though the file was deleted/re-written/deleted
        assertThat(source1.readUtf8()).isEqualTo(testString1)
        assertThat(source2.readUtf8()).isEqualTo(testString2)

        // now that the 2 sources have been fully read, you shouldn't be able to read it
        assertThat(fileSystem.exists("/boo")).isFalse()
    }

    private fun diffMe(first: String, second: String) {
        try {
            fileSystem.write(first, source(testString1))
        } catch (error: IOException) {
            throw RuntimeException("unable to write to $first", error)
        }

        try {
            assertThat(fileSystem.read(second).readUtf8()).isEqualTo(testString1)
        } catch (error: IOException) {
            throw RuntimeException("unable to read from $second", error)
        }

        try {
            fileSystem.write(second, source(testString2))
        } catch (error: IOException) {
            throw RuntimeException("unable to write to $second", error)
        }

        try {
            assertThat(fileSystem.read(first).readUtf8()).isEqualTo(testString2)
        } catch (error: IOException) {
            throw RuntimeException("unable to read from $first", error)
        }
    }

    companion object {
        private const val testString1 = "aszfbW#$%#$^&*5 r7ytjdfbv!@#R$\n@!#$%2354 wtyebfsdv\n"
        private const val testString2 = "#%^sdfvb#W%EtsdfbSER@#$%dsfb\nASRG \n #dsfvb \n"

        private fun source(data: String): BufferedSource = ByteArrayInputStream(data.toByteArray(UTF_8)).source().buffer()
    }
}
