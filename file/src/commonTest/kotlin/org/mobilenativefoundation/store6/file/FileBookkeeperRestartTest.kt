@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileBookkeeperRestartTest {
    @Test
    fun recordSuccess_withMeta_isPreservedOnReopen() =
        runTest {
            withFreshDirectory("store6-file-bookkeeper-restart") { directory ->
                val key = FileKitKey(StoreNamespace("restart-success"), "key")
                val meta = RestartTestMeta(writtenAtEpochMillis = 1_700_000_123L, etag = "etag-restart")
                val first = FileBookkeeper(directory)
                first.recordSuccess(key, meta)

                val reopened = FileBookkeeper(directory)
                val status = requireNotNull(reopened.status(key))
                val recoveredMeta = requireNotNull(status.meta)

                assertEquals(meta.writtenAtEpochMillis, recoveredMeta.writtenAtEpochMillis)
                assertEquals(meta.etag, recoveredMeta.etag)
                assertNotNull(status.lastSuccessSequence)
                assertNull(status.lastFailureAtEpochMillis)
                assertEquals(0, status.consecutiveFailures)
                assertFalse(status.durablyStale)
            }
        }

    @Test
    fun namespaceWatermark_keepsPreRestartSuccessDurablyStaleOnReopen() =
        runTest {
            withFreshDirectory("store6-file-bookkeeper-restart") { directory ->
                val namespace = StoreNamespace("restart-watermark")
                val key = FileKitKey(namespace, "key")
                val meta = RestartTestMeta(writtenAtEpochMillis = 10L, etag = "pre-watermark")
                val first = FileBookkeeper(directory)
                first.recordSuccess(key, meta)
                val successSequence = requireNotNull(first.status(key)).lastSuccessSequence
                first.advanceStaleWatermark(namespace)

                val reopened = FileBookkeeper(directory)
                val status = requireNotNull(reopened.status(key))
                val recoveredMeta = requireNotNull(status.meta)

                assertEquals(meta.writtenAtEpochMillis, recoveredMeta.writtenAtEpochMillis)
                assertEquals(meta.etag, recoveredMeta.etag)
                assertEquals(successSequence, status.lastSuccessSequence)
                assertTrue(status.durablyStale)
            }
        }

    @Test
    fun recoveredWatermark_outranksPreRestartSuccess_andPostRestartSuccessClearsStaleness() =
        runTest {
            withFreshDirectory("store6-file-bookkeeper-restart") { directory ->
                val namespace = StoreNamespace("restart-sequence")
                val refreshed = FileKitKey(namespace, "refreshed")
                val leftover = FileKitKey(namespace, "leftover")
                val first = FileBookkeeper(directory)
                first.recordSuccess(
                    refreshed,
                    RestartTestMeta(writtenAtEpochMillis = 10L, etag = "refreshed-old"),
                )
                first.recordSuccess(
                    leftover,
                    RestartTestMeta(writtenAtEpochMillis = 11L, etag = "leftover"),
                )
                first.advanceStaleWatermark(namespace)
                val leftoverSequence = requireNotNull(first.status(leftover)).lastSuccessSequence

                val reopened = FileBookkeeper(directory)
                assertTrue(requireNotNull(reopened.status(refreshed)).durablyStale)
                assertTrue(requireNotNull(reopened.status(leftover)).durablyStale)

                reopened.recordSuccess(
                    refreshed,
                    RestartTestMeta(writtenAtEpochMillis = 20L, etag = "refreshed-new"),
                )

                val refreshedStatus = requireNotNull(reopened.status(refreshed))
                val leftoverStatus = requireNotNull(reopened.status(leftover))
                assertEquals("refreshed-new", refreshedStatus.meta?.etag)
                assertFalse(refreshedStatus.durablyStale)
                assertTrue(
                    requireNotNull(refreshedStatus.lastSuccessSequence) >
                        requireNotNull(leftoverSequence),
                )
                assertEquals(leftoverSequence, leftoverStatus.lastSuccessSequence)
                assertTrue(leftoverStatus.durablyStale)
            }
        }

    @Test
    fun markStale_survivesReopen() =
        runTest {
            withFreshDirectory("store6-file-bookkeeper-restart") { directory ->
                val key = FileKitKey(StoreNamespace("restart-mark"), "key")
                val meta = RestartTestMeta(writtenAtEpochMillis = 10L, etag = "marked")
                val first = FileBookkeeper(directory)
                first.recordSuccess(key, meta)
                first.markStale(key)

                val reopened = FileBookkeeper(directory)
                val status = requireNotNull(reopened.status(key))
                val recoveredMeta = requireNotNull(status.meta)

                assertEquals(meta.writtenAtEpochMillis, recoveredMeta.writtenAtEpochMillis)
                assertEquals(meta.etag, recoveredMeta.etag)
                assertNotNull(status.lastSuccessSequence)
                assertTrue(status.durablyStale)
            }
        }

    @Test
    fun failureStreak_survivesReopen() =
        runTest {
            withFreshDirectory("store6-file-bookkeeper-restart") { directory ->
                val key = FileKitKey(StoreNamespace("restart-failure"), "key")
                val first = FileBookkeeper(directory)
                first.recordFailure(key, atEpochMillis = 10L)
                first.recordFailure(key, atEpochMillis = 20L)

                val reopened = FileBookkeeper(directory)
                val status = requireNotNull(reopened.status(key))

                assertNull(status.meta)
                assertNull(status.lastSuccessSequence)
                assertEquals(20L, status.lastFailureAtEpochMillis)
                assertEquals(2, status.consecutiveFailures)
                assertFalse(status.durablyStale)
            }
        }
}

private class RestartTestMeta(
    override val writtenAtEpochMillis: Long,
    override val etag: String?,
) : StoreMeta
