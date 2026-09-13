package org.mobilenativefoundation.store6.file.internal

import android.system.ErrnoException
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileOpsAndroidTest {
    @Test
    fun renameViaOs_translatesErrnoException() {
        val errnoException = ErrnoException("rename", 5)

        val error =
            assertFailsWith<IOException> {
                renameViaOs(Path("source"), Path("destination")) { _, _ ->
                    throw errnoException
                }
            }

        assertSame(errnoException, error.cause)
        assertTrue(error.message.orEmpty().contains("rename"))
    }

    @Test
    fun renameViaOs_completesWhenPrimitiveSucceeds() {
        var sourcePath: String? = null
        var destinationPath: String? = null

        renameViaOs(Path("source"), Path("destination")) { source, destination ->
            sourcePath = source
            destinationPath = destination
        }

        assertEquals("source", sourcePath)
        assertEquals("destination", destinationPath)
    }
}
