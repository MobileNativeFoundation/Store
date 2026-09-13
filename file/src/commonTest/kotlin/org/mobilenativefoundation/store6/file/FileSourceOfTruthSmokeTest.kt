@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.file.internal.ensureDirectories
import org.mobilenativefoundation.store6.file.internal.purgeRecursively
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSourceOfTruthSmokeTest {
    @Test
    fun writeThenRead_roundTrips() =
        runTest {
            withFreshDirectory { directory ->
                val source = FileSourceOfTruth<TestKey, String>(directory, Utf8StringFileCodec)
                val key = TestKey("round-trip")

                source.write(key, "value")

                assertEquals("value", source.reader(key).first())
            }
        }

    @Test
    fun delete_emitsNull() =
        runTest {
            withFreshDirectory { directory ->
                val source = FileSourceOfTruth<TestKey, String>(directory, Utf8StringFileCodec)
                val key = TestKey("delete")
                source.write(key, "value")

                source.delete(key)

                assertNull(source.reader(key).first())
            }
        }

    @Test
    fun equalValueRewrite_reEmits() =
        runTest {
            withFreshDirectory { directory ->
                val source = FileSourceOfTruth<TestKey, String>(directory, Utf8StringFileCodec)
                val key = TestKey("rewrite")
                source.write(key, "same")

                source.reader(key).test {
                    assertEquals("same", awaitItem())
                    source.write(key, "same")
                    assertEquals("same", awaitItem())
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

    @Test
    fun absentPath_firstEmissionIsNull() =
        runTest {
            withFreshDirectory { directory ->
                val source = FileSourceOfTruth<TestKey, String>(directory, Utf8StringFileCodec)

                assertNull(source.reader(TestKey("absent")).first())
            }
        }

    @Test
    fun oversizedCanonicalId_throwsWithoutTouchingDisk() =
        runTest {
            withFreshDirectory { directory ->
                val source = FileSourceOfTruth<TestKey, String>(directory, Utf8StringFileCodec)
                val key = TestKey("x".repeat(160))

                assertFailsWith<IllegalArgumentException> {
                    source.write(key, "value")
                }
                assertTrue(SystemFileSystem.list(directory).isEmpty())
            }
        }

    private suspend fun withFreshDirectory(block: suspend (Path) -> Unit) {
        val random = Random.nextLong().toULong().toString(radix = 16)
        val directory = Path(SystemTemporaryDirectory, "store6-file-sot-smoke-$random")
        ensureDirectories(directory)
        try {
            block(directory)
        } finally {
            purgeRecursively(directory)
        }
    }
}

private class TestKey(
    private val id: String,
    override val namespace: StoreNamespace = StoreNamespace("smoke"),
) : StoreKey {
    override fun canonicalId(): String = id
}
