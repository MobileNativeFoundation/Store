@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.file.internal.ensureDirectories
import org.mobilenativefoundation.store6.file.internal.purgeRecursively
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FileTrashLifecycleSmokeTest {
    @Test
    fun deleteNamespace_removesOnlyMatchingNamespace() =
        runTest {
            withFreshDirectory { directory ->
                val source = FileSourceOfTruth<TrashTestKey, String>(directory, Utf8StringFileCodec)
                val namespaceA = StoreNamespace("namespace-a")
                val namespaceB = StoreNamespace("namespace-b")
                val keyA = TrashTestKey("key", namespaceA)
                val keyB = TrashTestKey("key", namespaceB)
                source.write(keyA, "value-a")
                source.write(keyB, "value-b")

                source.deleteNamespace(namespaceA)

                assertNull(source.reader(keyA).first())
                assertEquals("value-b", source.reader(keyB).first())
            }
        }

    @Test
    fun deleteAll_succeedsTwiceConsecutively() =
        runTest {
            withFreshDirectory { directory ->
                val source = FileSourceOfTruth<TrashTestKey, String>(directory, Utf8StringFileCodec)
                val key = TrashTestKey("key")
                source.write(key, "value")

                source.deleteAll()
                assertNull(source.reader(key).first())

                source.deleteAll()
                assertNull(source.reader(key).first())
            }
        }

    @Test
    fun deleteNeverWrittenNamespace_reEmitsNullToActiveReader() =
        runTest {
            withFreshDirectory { directory ->
                val source = FileSourceOfTruth<TrashTestKey, String>(directory, Utf8StringFileCodec)
                val namespace = StoreNamespace("never-written")
                val key = TrashTestKey("key", namespace)

                source.reader(key).test {
                    assertNull(awaitItem())
                    source.deleteNamespace(namespace)
                    assertNull(awaitItem())
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

    @Test
    fun firstOperation_sweepsTemporaryAndTrashEntries() =
        runTest {
            withFreshDirectory { directory ->
                val staleTemporaryFile = Path(directory, "values-tmp", "stale")
                val staleTrashDirectory = Path(directory, "values-trash", "stale")
                val staleTrashFile = Path(staleTrashDirectory, "child")
                ensureDirectories(staleTemporaryFile.parent!!)
                ensureDirectories(staleTrashDirectory)
                write(staleTemporaryFile, "temporary")
                write(staleTrashFile, "trash")
                val source = FileSourceOfTruth<TrashTestKey, String>(directory, Utf8StringFileCodec)

                assertNull(source.reader(TrashTestKey("absent")).first())

                assertFalse(SystemFileSystem.exists(staleTemporaryFile))
                assertFalse(SystemFileSystem.exists(staleTrashDirectory))
            }
        }

    private suspend fun withFreshDirectory(block: suspend (Path) -> Unit) {
        val random = Random.nextLong().toULong().toString(radix = 16)
        val directory = Path(SystemTemporaryDirectory, "store6-file-trash-smoke-$random")
        ensureDirectories(directory)
        try {
            block(directory)
        } finally {
            purgeRecursively(directory)
        }
    }

    private fun write(
        path: Path,
        value: String,
    ) {
        SystemFileSystem.sink(path).buffered().use { sink ->
            sink.writeString(value)
        }
    }
}

private class TrashTestKey(
    private val id: String,
    override val namespace: StoreNamespace = StoreNamespace("trash-smoke"),
) : StoreKey {
    override fun canonicalId(): String = id
}
