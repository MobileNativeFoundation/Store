@file:OptIn(ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.room

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.testing.TestStoreMeta
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RoomBookkeeperTest {
    @Test
    fun watermarksAndSequence_surviveReopen(): TestResult = runTest {
        val path = newTempDatabasePath()
        val key = TestKey(namespace = "durable-watermark", id = "key")
        val firstDatabase = openTestDatabase(path)
        try {
            val first =
                RoomBookkeeper(firstDatabase, firstDatabase.store6BookkeeperDao())
            first.advanceStaleWatermark(key.namespace)
        } finally {
            firstDatabase.close()
        }

        val reopenedDatabase = openTestDatabase(path)
        try {
            val reopened =
                RoomBookkeeper(reopenedDatabase, reopenedDatabase.store6BookkeeperDao())

            val watermarkOnly = assertNotNull(reopened.status(key))
            assertNull(watermarkOnly.meta)
            assertNull(watermarkOnly.lastSuccessSequence)
            assertTrue(watermarkOnly.durablyStale)

            reopened.recordSuccess(key, TestStoreMeta(1L, "e1"))

            val afterSuccess = assertNotNull(reopened.status(key))
            assertEquals(1L, afterSuccess.meta?.writtenAtEpochMillis)
            assertEquals("e1", afterSuccess.meta?.etag)
            assertFalse(afterSuccess.durablyStale)
        } finally {
            reopenedDatabase.close()
        }
    }

    @Test
    fun sequence_monotoneAcrossReopen(): TestResult = runTest {
        val path = newTempDatabasePath()
        val key = TestKey(namespace = "durable-sequence", id = "key")
        val firstDatabase = openTestDatabase(path)
        try {
            val first =
                RoomBookkeeper(firstDatabase, firstDatabase.store6BookkeeperDao())
            first.recordSuccess(key, TestStoreMeta(1L, "e1"))
            assertEquals(1L, assertNotNull(first.status(key)).lastSuccessSequence)
        } finally {
            firstDatabase.close()
        }

        val reopenedDatabase = openTestDatabase(path)
        try {
            val reopened =
                RoomBookkeeper(reopenedDatabase, reopenedDatabase.store6BookkeeperDao())
            reopened.markStale(key)

            assertTrue(assertNotNull(reopened.status(key)).durablyStale)
        } finally {
            reopenedDatabase.close()
        }
    }

    @Test
    fun closedDatabase_recordSuccessAbsorbs_markStaleThrows(): TestResult = runTest {
        val database = openTestDatabase(newTempDatabasePath())
        val bookkeeper = RoomBookkeeper(database, database.store6BookkeeperDao())
        val key = TestKey(namespace = "closed", id = "key")
        try {
            database.close()

            bookkeeper.recordSuccess(key, TestStoreMeta(1L, "e1"))
            assertFailsWith<Throwable> {
                bookkeeper.markStale(key)
            }
            assertNull(bookkeeper.status(key))
        } finally {
            database.close()
        }
    }

    @Test
    fun forget_preservesWatermarks(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val bookkeeper = RoomBookkeeper(database, database.store6BookkeeperDao())
            val key = TestKey(namespace = "forget", id = "key")
            bookkeeper.advanceStaleWatermark(key.namespace)
            bookkeeper.recordSuccess(key, TestStoreMeta(1L, "e1"))

            bookkeeper.forget(key)

            val status = assertNotNull(bookkeeper.status(key))
            assertNull(status.meta)
            assertNull(status.lastSuccessSequence)
            assertNull(status.lastFailureAtEpochMillis)
            assertEquals(0, status.consecutiveFailures)
            assertTrue(status.durablyStale)
        } finally {
            database.close()
        }
    }

    @Test
    fun callerCancellation_recordSuccessPropagates(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val callerJob = CompletableDeferred<Job>()
            val cancellation = CancellationException("recordSuccess caller cancelled")
            val dao = CancelCallerOnRecordDao(callerJob, cancellation)
            val bookkeeper = RoomBookkeeper(database, dao)
            val returnedNormally = CompletableDeferred<Unit>()
            val propagatedCancellation = CompletableDeferred<Unit>()
            val child =
                launch {
                    callerJob.complete(currentCoroutineContext().job)
                    try {
                        bookkeeper.recordSuccess(
                            TestKey(namespace = "cancel-success", id = "key"),
                            TestStoreMeta(1L, "e1"),
                        )
                        returnedNormally.complete(Unit)
                    } catch (exception: CancellationException) {
                        propagatedCancellation.complete(Unit)
                        throw exception
                    }
                }

            child.join()

            assertTrue(dao.recordEntered.isCompleted)
            assertFalse(returnedNormally.isCompleted)
            assertTrue(propagatedCancellation.isCompleted)
            assertTrue(child.isCancelled)
        } finally {
            database.close()
        }
    }

    @Test
    fun callerCancellation_statusPropagates(): TestResult = runTest {
        val database = createTestDatabase()
        try {
            val callerJob = CompletableDeferred<Job>()
            val cancellation = CancellationException("status caller cancelled")
            val dao = CancelCallerOnRecordDao(callerJob, cancellation)
            val bookkeeper = RoomBookkeeper(database, dao)
            val returnedNormally = CompletableDeferred<Unit>()
            val propagatedCancellation = CompletableDeferred<Unit>()
            val child =
                launch {
                    callerJob.complete(currentCoroutineContext().job)
                    try {
                        bookkeeper.status(
                            TestKey(namespace = "cancel-status", id = "key"),
                        )
                        returnedNormally.complete(Unit)
                    } catch (exception: CancellationException) {
                        propagatedCancellation.complete(Unit)
                        throw exception
                    }
                }

            child.join()

            assertTrue(dao.recordEntered.isCompleted)
            assertFalse(returnedNormally.isCompleted)
            assertTrue(propagatedCancellation.isCompleted)
            assertTrue(child.isCancelled)
        } finally {
            database.close()
        }
    }

    private class TestKey(
        namespace: String,
        private val id: String,
    ) : StoreKey {
        override val namespace: StoreNamespace = StoreNamespace(namespace)

        override fun canonicalId(): String = id
    }

    private class CancelCallerOnRecordDao(
        private val callerJob: CompletableDeferred<Job>,
        private val cancellation: CancellationException,
    ) : Store6BookkeeperDao {
        val recordEntered = CompletableDeferred<Unit>()

        override suspend fun record(
            namespace: String,
            canonicalId: String,
        ): Store6BookkeepingEntity? {
            recordEntered.complete(Unit)
            callerJob.await().cancel(cancellation)
            throw cancellation
        }

        override suspend fun upsertRecord(record: Store6BookkeepingEntity) {
            error("Unexpected upsertRecord")
        }

        override suspend fun deleteRecord(
            namespace: String,
            canonicalId: String,
        ) {
            error("Unexpected deleteRecord")
        }

        override suspend fun deleteNamespaceRecords(namespace: String) {
            error("Unexpected deleteNamespaceRecords")
        }

        override suspend fun deleteAllRecords() {
            error("Unexpected deleteAllRecords")
        }

        override suspend fun watermark(scope: String): Long? = null

        override suspend fun upsertWatermark(watermark: Store6WatermarkEntity) = Unit
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
