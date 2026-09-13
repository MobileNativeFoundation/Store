@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.file.internal.Envelope
import org.mobilenativefoundation.store6.file.internal.FileNames
import org.mobilenativefoundation.store6.file.internal.ensureDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileBookkeeperCorruptionTest {
    @Test
    fun corruptRecord_withCoveringWatermark_quarantinesAndReportsWatermarkOnlyStatus() =
        runTest {
            withFreshDirectory("store6-file-bookkeeper-corruption") { directory ->
                val namespace = StoreNamespace("corrupt-record")
                val key = FileKitKey(namespace, "key")
                val first = FileBookkeeper(directory)
                first.recordSuccess(
                    key,
                    CorruptionTestMeta(writtenAtEpochMillis = 10L, etag = "doomed"),
                )
                first.advanceStaleWatermark(namespace)
                val canonicalPath =
                    FileNames.keyPath(
                        Path(directory, "bookkeeping", "records"),
                        namespace.value,
                        key.canonicalId(),
                    )
                val planted = ByteArray(Envelope.HEADER_BYTE_COUNT - 1) { 0x5A }
                overwrite(canonicalPath, planted)

                val reopened = FileBookkeeper(directory)
                val status = requireNotNull(reopened.status(key))
                val corruptPath = FileNames.corruptSibling(canonicalPath)

                assertNull(status.meta)
                assertNull(status.lastSuccessSequence)
                assertNull(status.lastFailureAtEpochMillis)
                assertEquals(0, status.consecutiveFailures)
                assertTrue(status.durablyStale)
                assertFalse(SystemFileSystem.exists(canonicalPath))
                assertTrue(SystemFileSystem.exists(corruptPath))
                assertTrue(planted.contentEquals(readFile(corruptPath)))
            }
        }

    @Test
    fun corruptWatermarks_outranksEverySurvivingSuccess_andReplacementIsExceededByNewSuccess() =
        runTest {
            withFreshDirectory("store6-file-bookkeeper-corruption") { directory ->
                val firstNamespace = StoreNamespace("corrupt-watermarks-a")
                val secondNamespace = StoreNamespace("corrupt-watermarks-b")
                val firstKey = FileKitKey(firstNamespace, "first")
                val secondKey = FileKitKey(firstNamespace, "second")
                val thirdKey = FileKitKey(secondNamespace, "third")
                val first = FileBookkeeper(directory)
                first.recordSuccess(
                    firstKey,
                    CorruptionTestMeta(writtenAtEpochMillis = 10L, etag = "first"),
                )
                first.recordSuccess(
                    secondKey,
                    CorruptionTestMeta(writtenAtEpochMillis = 20L, etag = "second"),
                )
                first.recordSuccess(
                    thirdKey,
                    CorruptionTestMeta(writtenAtEpochMillis = 30L, etag = "third"),
                )
                val watermarksPath = Path(directory, "bookkeeping", "watermarks")
                overwrite(
                    watermarksPath,
                    Envelope.write("NOPE", "not-watermarks".encodeToByteArray()),
                )

                val reopened = FileBookkeeper(directory)
                val firstStatus = requireNotNull(reopened.status(firstKey))
                val secondStatus = requireNotNull(reopened.status(secondKey))
                val thirdStatus = requireNotNull(reopened.status(thirdKey))

                assertEquals("first", firstStatus.meta?.etag)
                assertEquals("second", secondStatus.meta?.etag)
                assertEquals("third", thirdStatus.meta?.etag)
                assertTrue(firstStatus.durablyStale)
                assertTrue(secondStatus.durablyStale)
                assertTrue(thirdStatus.durablyStale)
                assertTrue(SystemFileSystem.exists(watermarksPath))
                assertTrue(SystemFileSystem.exists(FileNames.corruptSibling(watermarksPath)))

                reopened.recordSuccess(
                    firstKey,
                    CorruptionTestMeta(writtenAtEpochMillis = 40L, etag = "fresh"),
                )

                val refreshed = requireNotNull(reopened.status(firstKey))
                assertEquals("fresh", refreshed.meta?.etag)
                assertFalse(refreshed.durablyStale)
                assertTrue(requireNotNull(reopened.status(secondKey)).durablyStale)
                assertTrue(requireNotNull(reopened.status(thirdKey)).durablyStale)
            }
        }

    @Test
    fun corruptWatermarks_withNoSurvivingRecords_recoversAndFreshSuccessIsNotStale() =
        runTest {
            withFreshDirectory("store6-file-bookkeeper-corruption") { directory ->
                val watermarksPath = Path(directory, "bookkeeping", "watermarks")
                ensureDirectories(Path(directory, "bookkeeping"))
                overwrite(watermarksPath, ByteArray(Envelope.HEADER_BYTE_COUNT - 1) { 0x3C })

                val reopened = FileBookkeeper(directory)
                val key = FileKitKey(StoreNamespace("corrupt-watermarks-empty"), "fresh")
                val unseen = requireNotNull(reopened.status(key))

                assertNull(unseen.meta)
                assertNull(unseen.lastSuccessSequence)
                assertNull(unseen.lastFailureAtEpochMillis)
                assertEquals(0, unseen.consecutiveFailures)
                assertTrue(unseen.durablyStale)
                assertTrue(SystemFileSystem.exists(watermarksPath))
                assertTrue(SystemFileSystem.exists(FileNames.corruptSibling(watermarksPath)))

                reopened.recordSuccess(
                    key,
                    CorruptionTestMeta(writtenAtEpochMillis = 50L, etag = "after-empty-recovery"),
                )

                val fresh = requireNotNull(reopened.status(key))
                assertEquals("after-empty-recovery", fresh.meta?.etag)
                assertTrue(requireNotNull(fresh.lastSuccessSequence) >= 2L)
                assertFalse(fresh.durablyStale)
            }
        }
}

private class CorruptionTestMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String?,
) : StoreMeta

private fun overwrite(
    path: Path,
    bytes: ByteArray,
) {
    SystemFileSystem.sink(path).buffered().use { sink ->
        sink.write(bytes)
    }
}

private fun readFile(path: Path): ByteArray =
    SystemFileSystem.source(path).buffered().use { source ->
        source.readByteArray()
    }
