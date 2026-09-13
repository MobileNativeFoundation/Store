@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.file.internal.FileNames
import org.mobilenativefoundation.store6.file.internal.ensureDirectories
import org.mobilenativefoundation.store6.file.internal.purgeRecursively
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileBookkeeperRecoverySmokeTest {
    @Test
    fun reopen_preservesStatus() =
        runTest {
            withFreshDirectory { directory ->
                val key = RecoveryTestKey("reopen")
                val meta = RecoveryTestMeta(writtenAtEpochMillis = 123L, etag = "etag-reopen")
                val first = FileBookkeeper(directory)
                first.recordSuccess(key, meta)
                val expected = first.status(key)

                val reopened = FileBookkeeper(directory)
                val actual = reopened.status(key)

                assertEquals(expected?.meta?.writtenAtEpochMillis, actual?.meta?.writtenAtEpochMillis)
                assertEquals(expected?.meta?.etag, actual?.meta?.etag)
                assertEquals(expected?.lastSuccessSequence, actual?.lastSuccessSequence)
                assertEquals(expected?.lastFailureAtEpochMillis, actual?.lastFailureAtEpochMillis)
                assertEquals(expected?.consecutiveFailures, actual?.consecutiveFailures)
                assertEquals(expected?.durablyStale, actual?.durablyStale)
            }
        }

    @Test
    fun sequenceRemainsMonotonicAcrossReopen() =
        runTest {
            withFreshDirectory { directory ->
                val namespace = StoreNamespace("recovery-sequence")
                val key = RecoveryTestKey("key", namespace)
                val first = FileBookkeeper(directory)
                first.recordSuccess(
                    key,
                    RecoveryTestMeta(writtenAtEpochMillis = 10L, etag = null),
                )
                first.advanceStaleWatermark(namespace)

                val reopened = FileBookkeeper(directory)
                assertTrue(reopened.status(key)?.durablyStale == true)

                reopened.recordSuccess(
                    key,
                    RecoveryTestMeta(writtenAtEpochMillis = 20L, etag = "newer"),
                )

                val refreshed = reopened.status(key)
                assertEquals(3L, refreshed?.lastSuccessSequence)
                assertFalse(refreshed?.durablyStale ?: true)
            }
        }

    @Test
    fun corruptRecordWithIntactWatermark_isQuarantinedAndReportsWatermarkOnlyStatus() =
        runTest {
            withFreshDirectory { directory ->
                val namespace = StoreNamespace("recovery-corrupt-record")
                val key = RecoveryTestKey("key", namespace)
                val first = FileBookkeeper(directory)
                first.recordSuccess(
                    key,
                    RecoveryTestMeta(writtenAtEpochMillis = 10L, etag = null),
                )
                first.advanceStaleWatermark(namespace)
                val canonicalPath =
                    FileNames.keyPath(
                        Path(directory, "bookkeeping", "records"),
                        namespace.value,
                        key.canonicalId(),
                    )
                overwrite(canonicalPath, "not-an-envelope".encodeToByteArray())

                val reopened = FileBookkeeper(directory)
                val status = reopened.status(key)

                assertNull(status?.meta)
                assertNull(status?.lastSuccessSequence)
                assertNull(status?.lastFailureAtEpochMillis)
                assertEquals(0, status?.consecutiveFailures)
                assertTrue(status?.durablyStale == true)
                assertFalse(SystemFileSystem.exists(canonicalPath))
                assertTrue(SystemFileSystem.exists(FileNames.corruptSibling(canonicalPath)))
            }
        }

    @Test
    fun corruptWatermarks_replacesWithoutMissingCanonicalWindowAndOutranksSurvivingSuccesses() =
        runTest {
            withFreshDirectory { directory ->
                val namespace = StoreNamespace("recovery-corrupt-watermarks")
                val firstKey = RecoveryTestKey("first", namespace)
                val secondKey = RecoveryTestKey("second", namespace)
                val first = FileBookkeeper(directory)
                first.recordSuccess(
                    firstKey,
                    RecoveryTestMeta(writtenAtEpochMillis = 10L, etag = "first"),
                )
                first.recordSuccess(
                    secondKey,
                    RecoveryTestMeta(writtenAtEpochMillis = 20L, etag = "second"),
                )
                val watermarksPath = Path(directory, "bookkeeping", "watermarks")
                overwrite(watermarksPath, "not-an-envelope".encodeToByteArray())
                var replacementWriteObserved = false
                val reopened =
                    FileBookkeeper(
                        directory = directory,
                        ioContext = Dispatchers.Default,
                        beforeDiskWriteTestGate = {
                            assertTrue(SystemFileSystem.exists(watermarksPath))
                            replacementWriteObserved = true
                        },
                    )

                assertTrue(reopened.status(firstKey)?.durablyStale == true)
                assertTrue(reopened.status(secondKey)?.durablyStale == true)
                assertTrue(replacementWriteObserved)
                assertTrue(SystemFileSystem.exists(watermarksPath))
                assertTrue(SystemFileSystem.exists(FileNames.corruptSibling(watermarksPath)))

                reopened.recordSuccess(
                    firstKey,
                    RecoveryTestMeta(writtenAtEpochMillis = 30L, etag = "fresh"),
                )

                val refreshed = reopened.status(firstKey)
                assertEquals(4L, refreshed?.lastSuccessSequence)
                assertFalse(refreshed?.durablyStale ?: true)
            }
        }

    private suspend fun withFreshDirectory(block: suspend (Path) -> Unit) {
        val random = Random.nextLong().toULong().toString(radix = 16)
        val directory = Path(SystemTemporaryDirectory, "store6-file-bookkeeper-recovery-$random")
        ensureDirectories(directory)
        try {
            block(directory)
        } finally {
            purgeRecursively(directory)
        }
    }

    private fun overwrite(
        path: Path,
        bytes: ByteArray,
    ) {
        SystemFileSystem.sink(path).buffered().use { sink ->
            sink.write(bytes)
        }
    }
}

private class RecoveryTestKey(
    private val id: String,
    override val namespace: StoreNamespace = StoreNamespace("bookkeeper-recovery"),
) : StoreKey {
    override fun canonicalId(): String = id
}

private class RecoveryTestMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String?,
) : StoreMeta
