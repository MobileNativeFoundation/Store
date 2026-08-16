@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal class DriverAccessConcurrencyJvmTest {
    @Test
    fun concurrentCrossKeyWrites_shareOneDriverGate() = runTest {
        withHarness { harness ->
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondStarted = CountDownLatch(1)
            val secondEntered = CountDownLatch(1)
            val sourceOfTruth =
                sourceOfTruth(harness) { key, value ->
                    when (key) {
                        KEY_A -> {
                            harness.upsertRow(key.ns, key.id, value)
                            firstEntered.countDown()
                            assertTrue(releaseFirst.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        }
                        KEY_B -> {
                            secondEntered.countDown()
                            harness.upsertRow(key.ns, key.id, value)
                        }
                    }
                }

            val firstWrite = async(Dispatchers.Default) { sourceOfTruth.write(KEY_A, "alpha") }
            try {
                assertTrue(firstEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val secondWrite =
                    async(Dispatchers.Default) {
                        secondStarted.countDown()
                        sourceOfTruth.write(KEY_B, "bravo")
                    }

                assertTrue(secondStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(secondEntered.await(OVERLAP_PROBE_MILLIS, TimeUnit.MILLISECONDS))
                releaseFirst.countDown()
                firstWrite.await()
                secondWrite.await()
            } finally {
                releaseFirst.countDown()
            }

            assertEquals("alpha", harness.selectRow(KEY_A.ns, KEY_A.id).executeAsOneOrNull())
            assertEquals("bravo", harness.selectRow(KEY_B.ns, KEY_B.id).executeAsOneOrNull())
        }
    }

    @Test
    fun sourceOfTruthAndBookkeeper_shareOneDriverGate() = runTest {
        withHarness { harness ->
            val writeEntered = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val maintenanceStarted = CountDownLatch(1)
            val maintenanceCompleted = CountDownLatch(1)
            val sourceOfTruth =
                sourceOfTruth(harness) { key, value ->
                    harness.upsertRow(key.ns, key.id, value)
                    writeEntered.countDown()
                    assertTrue(releaseWrite.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                }
            val bookkeeper = SqlDelightBookkeeper(harness.driver, harness.transacter)

            val write = async(Dispatchers.Default) { sourceOfTruth.write(KEY_A, "alpha") }
            try {
                assertTrue(writeEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val maintenance =
                    async(Dispatchers.Default) {
                        maintenanceStarted.countDown()
                        bookkeeper.advanceGlobalStaleWatermark()
                        maintenanceCompleted.countDown()
                    }

                assertTrue(maintenanceStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(
                    maintenanceCompleted.await(OVERLAP_PROBE_MILLIS, TimeUnit.MILLISECONDS),
                )
                releaseWrite.countDown()
                write.await()
                maintenance.await()
            } finally {
                releaseWrite.countDown()
            }

            assertNotNull(harness.watermark("global"))
        }
    }

    @Test
    fun readerQueryExecution_waitsForSameDriverWrite() = runTest {
        withHarness { harness ->
            val writeEntered = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val readerStarted = CountDownLatch(1)
            val queryExecuted = CountDownLatch(1)
            val sourceOfTruth =
                sourceOfTruth(
                    harness = harness,
                    readQuery = { key ->
                        ProbedQuery(harness.selectRow(key.ns, key.id), queryExecuted)
                    },
                ) { key, value ->
                    harness.upsertRow(key.ns, key.id, value)
                    writeEntered.countDown()
                    assertTrue(releaseWrite.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                }

            val write = async(Dispatchers.Default) { sourceOfTruth.write(KEY_A, "alpha") }
            try {
                assertTrue(writeEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val read =
                    async(Dispatchers.Default) {
                        readerStarted.countDown()
                        sourceOfTruth.reader(KEY_B).first()
                    }

                assertTrue(readerStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(queryExecuted.await(OVERLAP_PROBE_MILLIS, TimeUnit.MILLISECONDS))
                releaseWrite.countDown()
                write.await()
                assertNull(read.await())
                assertTrue(queryExecuted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            } finally {
                releaseWrite.countDown()
            }
        }
    }

    @Test
    fun foreignThreadResumption_cannotEnterDriverBeforeSuspensionIsRejected() = runTest {
        withHarness { harness ->
            val foreignWriteEntered = CountDownLatch(1)
            val workerFinished = CountDownLatch(1)
            val sourceOfTruth =
                sourceOfTruth(harness) { key, value ->
                    foreignWriteEntered.countDown()
                    harness.upsertRow(key.ns, key.id, value)
                }

            val failure =
                assertFailsWith<IllegalStateException> {
                    sourceOfTruth.withTransaction {
                        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                            // Resume on another thread before this suspension callback returns.
                            // A context-only lease lets that thread enter writeRow concurrently.
                            // Thread affinity must make it wait for the driver gate instead.
                            Thread(
                                {
                                    try {
                                        continuation.resume(Unit)
                                    } finally {
                                        workerFinished.countDown()
                                    }
                                },
                                "store6-sqldelight-forced-dispatch",
                            ).apply {
                                isDaemon = true
                                start()
                            }
                            foreignWriteEntered.await(
                                OVERLAP_PROBE_MILLIS,
                                TimeUnit.MILLISECONDS,
                            )
                            COROUTINE_SUSPENDED
                        }
                        sourceOfTruth.write(KEY_A, "foreign")
                    }
                }

            assertEquals(SUSPENDING_BLOCK_MESSAGE, failure.message)
            assertFalse(foreignWriteEntered.await(50L, TimeUnit.MILLISECONDS))
            assertTrue(workerFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertNull(harness.selectRow(KEY_A.ns, KEY_A.id).executeAsOneOrNull())
            assertNull(harness.metaRow(KEY_A.ns, KEY_A.id))
        }
    }

    @Test
    fun contendedGate_nestedReentryUsesAcquisitionThread() = runTest {
        withHarness { harness ->
            val holdingWriteEntered = CountDownLatch(1)
            val releaseHoldingWrite = CountDownLatch(1)
            val sourceOfTruth =
                sourceOfTruth(harness) { key, value ->
                    harness.upsertRow(key.ns, key.id, value)
                    if (key == KEY_A) {
                        holdingWriteEntered.countDown()
                        assertTrue(
                            releaseHoldingWrite.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        )
                    }
                }

            val holdingWrite = async(Dispatchers.Default) { sourceOfTruth.write(KEY_A, "alpha") }
            try {
                assertTrue(holdingWriteEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                AlternatingDispatcher().use { dispatcher ->
                    val nestedTransaction =
                        async(dispatcher) {
                            sourceOfTruth.withTransaction {
                                sourceOfTruth.write(KEY_B, "bravo")
                            }
                        }

                    assertTrue(
                        dispatcher.firstDispatchReturned.await(
                            TIMEOUT_SECONDS,
                            TimeUnit.SECONDS,
                        ),
                    )
                    releaseHoldingWrite.countDown()
                    holdingWrite.await()
                    withContext(Dispatchers.Default) {
                        nestedTransaction.await()
                    }
                }
            } finally {
                releaseHoldingWrite.countDown()
            }

            assertEquals("alpha", harness.selectRow(KEY_A.ns, KEY_A.id).executeAsOneOrNull())
            assertEquals("bravo", harness.selectRow(KEY_B.ns, KEY_B.id).executeAsOneOrNull())
        }
    }

    private fun sourceOfTruth(
        harness: SqlHarness,
        readQuery: (SqlTestKey) -> Query<String> = { key ->
            harness.selectRow(key.ns, key.id)
        },
        writeRow: (SqlTestKey, String) -> Unit,
    ): SqlDelightSourceOfTruth<SqlTestKey, String> =
        SqlDelightSourceOfTruth(
            driver = harness.driver,
            transacter = harness.transacter,
            readQuery = readQuery,
            writeRow = writeRow,
            deleteRow = { key -> harness.deleteRow(key.ns, key.id) },
            deleteNamespaceRows = { namespace -> harness.deleteNamespace(namespace.value) },
            deleteAllRows = harness::deleteAll,
        )

    private suspend fun <R> withHarness(block: suspend (SqlHarness) -> R): R {
        val harness = freshHarness()
        return try {
            block(harness)
        } finally {
            harness.driver.close()
        }
    }

    private class ProbedQuery(
        private val delegate: Query<String>,
        private val executed: CountDownLatch,
    ) : Query<String>({ cursor -> cursor.getString(0)!! }) {
        override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> {
            executed.countDown()
            return delegate.execute(mapper)
        }

        override fun addListener(listener: Listener) {
            delegate.addListener(listener)
        }

        override fun removeListener(listener: Listener) {
            delegate.removeListener(listener)
        }
    }

    private class AlternatingDispatcher : CoroutineDispatcher(), AutoCloseable {
        val firstDispatchReturned = CountDownLatch(1)
        private val dispatchIndex = AtomicInteger()
        private val firstExecutor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "store6-sqldelight-gate-waiter")
            }
        private val secondExecutor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "store6-sqldelight-gate-owner")
            }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val index = dispatchIndex.getAndIncrement()
            val executor = if (index % 2 == 0) firstExecutor else secondExecutor
            executor.execute {
                try {
                    block.run()
                } finally {
                    if (index == 0) firstDispatchReturned.countDown()
                }
            }
        }

        override fun close() {
            firstExecutor.shutdownNow()
            secondExecutor.shutdownNow()
        }
    }

    private companion object {
        val KEY_A = SqlTestKey(ns = "users", id = "a")
        val KEY_B = SqlTestKey(ns = "users", id = "b")
        const val TIMEOUT_SECONDS = 5L
        const val OVERLAP_PROBE_MILLIS = 250L
        const val SUSPENDING_BLOCK_MESSAGE =
            "withTransaction block suspended while executing against a synchronous SQLDelight " +
                "driver for this store. Keep the block to synchronous same-database statements " +
                "(this adapter's write/delete and other SQLDelight statements); move asynchronous " +
                "work outside withTransaction."
    }
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
