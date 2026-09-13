@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.file.internal.ensureDirectories
import org.mobilenativefoundation.store6.file.internal.purgeRecursively
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileBookkeeperSmokeTest {
    @Test
    fun recordSuccess_statusPreservesMeta() =
        runTest {
            withFreshDirectory { directory ->
                val bookkeeper = FileBookkeeper(directory)
                val key = BookkeeperTestKey("success")
                val meta = BookkeeperTestMeta(writtenAtEpochMillis = 123L, etag = "etag-1")

                bookkeeper.recordSuccess(key, meta)

                val status = bookkeeper.status(key)
                assertEquals(123L, status?.meta?.writtenAtEpochMillis)
                assertEquals("etag-1", status?.meta?.etag)
                assertEquals(1L, status?.lastSuccessSequence)
                assertNull(status?.lastFailureAtEpochMillis)
                assertEquals(0, status?.consecutiveFailures)
                assertFalse(status?.durablyStale ?: true)
            }
        }

    @Test
    fun failureStreak_successResetsCountAndTimestamp() =
        runTest {
            withFreshDirectory { directory ->
                val bookkeeper = FileBookkeeper(directory)
                val key = BookkeeperTestKey("failure-streak")

                bookkeeper.recordFailure(key, atEpochMillis = 10L)
                bookkeeper.recordFailure(key, atEpochMillis = 20L)

                val failedStatus = bookkeeper.status(key)
                assertEquals(20L, failedStatus?.lastFailureAtEpochMillis)
                assertEquals(2, failedStatus?.consecutiveFailures)
                assertFalse(failedStatus?.durablyStale ?: true)

                bookkeeper.recordSuccess(
                    key,
                    BookkeeperTestMeta(writtenAtEpochMillis = 30L, etag = null),
                )

                val successfulStatus = bookkeeper.status(key)
                assertNull(successfulStatus?.lastFailureAtEpochMillis)
                assertEquals(0, successfulStatus?.consecutiveFailures)
            }
        }

    @Test
    fun markStale_makesStatusDurablyStale() =
        runTest {
            withFreshDirectory { directory ->
                val bookkeeper = FileBookkeeper(directory)
                val key = BookkeeperTestKey("mark-stale")
                bookkeeper.recordSuccess(
                    key,
                    BookkeeperTestMeta(writtenAtEpochMillis = 10L, etag = null),
                )

                bookkeeper.markStale(key)

                assertTrue(bookkeeper.status(key)?.durablyStale == true)
            }
        }

    @Test
    fun namespaceWatermark_isOutrankedByNewerSuccess() =
        runTest {
            withFreshDirectory { directory ->
                val bookkeeper = FileBookkeeper(directory)
                val namespace = StoreNamespace("watermark")
                val key = BookkeeperTestKey("key", namespace)
                bookkeeper.recordSuccess(
                    key,
                    BookkeeperTestMeta(writtenAtEpochMillis = 10L, etag = null),
                )

                bookkeeper.advanceStaleWatermark(namespace)
                assertTrue(bookkeeper.status(key)?.durablyStale == true)

                bookkeeper.recordSuccess(
                    key,
                    BookkeeperTestMeta(writtenAtEpochMillis = 20L, etag = null),
                )
                assertFalse(bookkeeper.status(key)?.durablyStale ?: true)
            }
        }

    @Test
    fun forget_preservesCoveringWatermark() =
        runTest {
            withFreshDirectory { directory ->
                val bookkeeper = FileBookkeeper(directory)
                val namespace = StoreNamespace("forget-watermark")
                val key = BookkeeperTestKey("key", namespace)
                bookkeeper.recordSuccess(
                    key,
                    BookkeeperTestMeta(writtenAtEpochMillis = 10L, etag = null),
                )
                bookkeeper.advanceStaleWatermark(namespace)

                bookkeeper.forget(key)

                val status = bookkeeper.status(key)
                assertNull(status?.meta)
                assertNull(status?.lastSuccessSequence)
                assertNull(status?.lastFailureAtEpochMillis)
                assertEquals(0, status?.consecutiveFailures)
                assertTrue(status?.durablyStale == true)
            }
        }

    @Test
    fun recordSuccess_absorbsInjectedWriteFailureAndUpdatesMirror() =
        runTest {
            withFreshDirectory { directory ->
                val bookkeeper =
                    FileBookkeeper(
                        directory = directory,
                        ioContext = Dispatchers.Default,
                        beforeDiskWriteTestGate = { throw IOException("injected write failure") },
                    )
                val key = BookkeeperTestKey("absorbed-failure")
                val meta = BookkeeperTestMeta(writtenAtEpochMillis = 10L, etag = "local")

                bookkeeper.recordSuccess(key, meta)

                assertEquals("local", bookkeeper.status(key)?.meta?.etag)
            }
        }

    @Test
    fun recordSuccess_propagatesInjectedVmFailure() =
        runTest {
            withFreshDirectory { directory ->
                val bookkeeper =
                    FileBookkeeper(
                        directory = directory,
                        ioContext = Dispatchers.Default,
                        beforeDiskWriteTestGate = { throw AssertionError("injected VM failure") },
                    )
                val key = BookkeeperTestKey("vm-failure")
                val meta = BookkeeperTestMeta(writtenAtEpochMillis = 10L, etag = "local")

                assertFailsWith<AssertionError> {
                    bookkeeper.recordSuccess(key, meta)
                }
            }
        }

    @Test
    fun recordFailure_propagatesInjectedVmFailure() =
        runTest {
            withFreshDirectory { directory ->
                val bookkeeper =
                    FileBookkeeper(
                        directory = directory,
                        ioContext = Dispatchers.Default,
                        beforeDiskWriteTestGate = { throw AssertionError("injected VM failure") },
                    )
                val key = BookkeeperTestKey("vm-failure")

                assertFailsWith<AssertionError> {
                    bookkeeper.recordFailure(key, atEpochMillis = 42L)
                }
            }
        }

    @Test
    fun markStale_propagatesInjectedWriteFailureWithoutUpdatingMirror() =
        runTest {
            withFreshDirectory { directory ->
                var failWrites = false
                val bookkeeper =
                    FileBookkeeper(
                        directory = directory,
                        ioContext = Dispatchers.Default,
                        beforeDiskWriteTestGate = {
                            if (failWrites) throw IOException("injected write failure")
                        },
                    )
                val key = BookkeeperTestKey("atomic-failure")
                bookkeeper.recordSuccess(
                    key,
                    BookkeeperTestMeta(writtenAtEpochMillis = 10L, etag = null),
                )
                failWrites = true

                assertFailsWith<IOException> {
                    bookkeeper.markStale(key)
                }

                val status = bookkeeper.status(key)
                assertEquals(1L, status?.lastSuccessSequence)
                assertFalse(status?.durablyStale ?: true)
            }
        }

    private suspend fun withFreshDirectory(block: suspend (Path) -> Unit) {
        val random = Random.nextLong().toULong().toString(radix = 16)
        val directory = Path(SystemTemporaryDirectory, "store6-file-bookkeeper-smoke-$random")
        ensureDirectories(directory)
        try {
            block(directory)
        } finally {
            purgeRecursively(directory)
        }
    }
}

private class BookkeeperTestKey(
    private val id: String,
    override val namespace: StoreNamespace = StoreNamespace("bookkeeper-smoke"),
) : StoreKey {
    override fun canonicalId(): String = id
}

private class BookkeeperTestMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String?,
) : StoreMeta
