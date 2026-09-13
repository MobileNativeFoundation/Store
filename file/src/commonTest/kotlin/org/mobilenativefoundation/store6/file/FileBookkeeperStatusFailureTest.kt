@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.file.internal.FileNames
import org.mobilenativefoundation.store6.file.internal.atomicReplace
import org.mobilenativefoundation.store6.testing.TestStoreMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cold-recovery failures on the status path must reach the caller.
 *
 * `status` answers from the process-local mirror, and the only storage read it makes is the
 * first-operation recovery. Decision D4 routes a failed status read through the typed persistence
 * error channel and keeps freshness conservative, so that read must never be absorbed into
 * `null` ("no record") or into a fresh answer, and a failed recovery must not mark the mirror
 * initialized.
 */
class FileBookkeeperStatusFailureTest {
    @Test
    fun coldStatus_recordsSubtreeRootReplacedByFile_propagatesThenRecovers() =
        runTest {
            withFreshDirectory("store6-file-status-failure-root") { directory ->
                val key = FileKitKey(StoreNamespace("status"), "key")
                val seeded = TestStoreMeta(writtenAtEpochMillis = 11L, etag = "seeded")
                FileBookkeeper(directory).recordSuccess(key, seeded)

                val recordsDirectory = Path(directory, "bookkeeping", "records")
                val stashedRecords = Path(directory, "bookkeeping", "records-stash")
                atomicReplace(recordsDirectory, stashedRecords)
                SystemFileSystem.sink(recordsDirectory).buffered().use { sink ->
                    sink.write("not-a-directory".encodeToByteArray())
                }

                val reopened = FileBookkeeper(directory)
                // A returned value of any shape, including null, would be the absorbed answer
                // D4 forbids.
                assertFailsWith<IOException>("Cold recovery failure must reach the caller") {
                    reopened.status(key)
                }

                SystemFileSystem.delete(recordsDirectory, mustExist = true)
                atomicReplace(stashedRecords, recordsDirectory)

                val recovered = requireNotNull(reopened.status(key))
                assertEquals(seeded.writtenAtEpochMillis, recovered.meta?.writtenAtEpochMillis)
                assertEquals(seeded.etag, recovered.meta?.etag)
                assertEquals(1L, recovered.lastSuccessSequence)
                assertFalse(recovered.durablyStale)
            }
        }

    @Test
    fun coldStatus_failureInsideRecovery_propagatesThenRecovers() =
        runTest {
            withFreshDirectory("store6-file-status-failure-inside") { directory ->
                val key = FileKitKey(StoreNamespace("status"), "key")
                val seeded = TestStoreMeta(writtenAtEpochMillis = 12L, etag = "seeded")
                FileBookkeeper(directory).recordSuccess(key, seeded)

                val watermarksPath = Path(directory, "bookkeeping", "watermarks")
                SystemFileSystem.sink(watermarksPath).buffered().use { sink ->
                    sink.write("not-an-envelope".encodeToByteArray())
                }

                // Recovery replaces corrupt watermarks with a fresh file. Fail that write once,
                // after the read and quarantine copy have already run.
                var pendingWriteFailures = 1
                val reopened =
                    FileBookkeeper(
                        directory = directory,
                        ioContext = Dispatchers.Default,
                        beforeDiskWriteTestGate = {
                            if (pendingWriteFailures > 0) {
                                pendingWriteFailures -= 1
                                throw IOException("injected recovery write failure")
                            }
                        },
                    )

                assertFailsWith<IOException>("A failure inside recovery must reach the caller") {
                    reopened.status(key)
                }

                // The same instance must recover, which it can only do if the failed attempt left
                // the mirror uninitialized.
                val recovered = requireNotNull(reopened.status(key))
                assertEquals(seeded.etag, recovered.meta?.etag)
                assertEquals(1L, recovered.lastSuccessSequence)
                assertTrue(recovered.durablyStale, "Replacement watermarks must outrank the survivor")
                assertTrue(SystemFileSystem.exists(FileNames.corruptSibling(watermarksPath)))
            }
        }
}
