@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.sqldelight

import app.cash.turbine.test
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.mobilenativefoundation.store6.core.StoreMeta
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class SqlDelightAtomicityTest {
    @Test
    fun userStatementFailure_rollsBackValueAndSidecarStamp() = runTest {
        withHarness { harness ->
            val sourceOfTruth =
                sourceOfTruth(harness) { key, value ->
                    harness.upsertRow(key.ns, key.id, value)
                    throw InjectedFailure("user statement failed")
                }

            assertFailsWith<InjectedFailure> {
                sourceOfTruth.write(KEY_A, "value")
            }

            assertValueAndSidecarAbsent(harness, KEY_A)
        }
    }

    @Test
    fun sidecarStampFailure_rollsBackUserRow() = runTest {
        withHarness { harness ->
            val sourceOfTruth = sqlDelightTestSot<SqlTestKey, String>(harness)
            harness.executeRaw(FAIL_META_INSERT_TRIGGER)

            assertFails {
                sourceOfTruth.write(KEY_A, "value")
            }
            assertValueAndSidecarAbsent(harness, KEY_A)

            harness.executeRaw("DROP TRIGGER store6_test_fail")
            sourceOfTruth.write(KEY_A, "recovered")

            assertEquals("recovered", harness.selectRow(KEY_A.ns, KEY_A.id).executeAsOneOrNull())
            assertNotNull(harness.metaRow(KEY_A.ns, KEY_A.id))
        }
    }

    @Test
    fun withTransaction_blockFailure_rollsBackEchoAndRetire() = runTest {
        withHarness { harness ->
            val sourceOfTruth = sqlDelightTestSot<SqlTestKey, String>(harness)

            assertFailsWith<InjectedFailure> {
                sourceOfTruth.withTransaction {
                    sourceOfTruth.write(KEY_A, "echo")
                    harness.upsertScratch(SCRATCH_ID, "retired")
                    throw InjectedFailure("transaction block failed")
                }
            }

            assertValueAndSidecarAbsent(harness, KEY_A)
            assertNull(harness.selectScratch(SCRATCH_ID))
        }
    }

    @Test
    fun withTransaction_commit_appliesBothAndNotifiesOnce() = runTest {
        withHarness { harness ->
            val sourceOfTruth = sqlDelightTestSot<SqlTestKey, String>(harness)

            sourceOfTruth.reader(KEY_A).test {
                assertNull(awaitItem())

                sourceOfTruth.withTransaction {
                    sourceOfTruth.write(KEY_A, "echo")
                    harness.upsertScratch(SCRATCH_ID, "retired")
                    expectNoEvents()
                }

                assertEquals("echo", harness.selectRow(KEY_A.ns, KEY_A.id).executeAsOneOrNull())
                assertEquals("retired", harness.selectScratch(SCRATCH_ID))
                assertEquals("echo", awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun withTransaction_suspendingBlock_failsFastAndRollsBack() = runTest {
        withHarness { harness ->
            val sourceOfTruth = sqlDelightTestSot<SqlTestKey, String>(harness)
            var lateResume: CancellableContinuation<Unit>? = null

            val failure =
                assertFailsWith<IllegalStateException> {
                    sourceOfTruth.withTransaction {
                        sourceOfTruth.write(KEY_A, "partial")
                        suspendCancellableCoroutine { continuation ->
                            lateResume = continuation
                        }
                        sourceOfTruth.write(KEY_A, "late")
                    }
                }

            assertEquals(SUSPENDING_BLOCK_MESSAGE, failure.message)
            assertValueAndSidecarAbsent(harness, KEY_A)

            lateResume!!.resume(Unit)
            assertValueAndSidecarAbsent(harness, KEY_A)
        }
    }

    @Test
    fun withTransaction_nestedSourceOfTruthAndBookkeeperCalls_areReentrant() = runTest {
        withHarness { harness ->
            val sourceOfTruth = sqlDelightTestSot<SqlTestKey, String>(harness)
            val bookkeeper = SqlDelightBookkeeper(harness.driver, harness.transacter)
            val meta =
                object : StoreMeta {
                    override val writtenAtEpochMillis: Long = 42L
                    override val etag: String = "v1"
                }

            withTimeout(5_000) {
                sourceOfTruth.withTransaction {
                    sourceOfTruth.write(KEY_A, "value")
                    bookkeeper.recordSuccess(KEY_A, meta)
                }
            }

            assertEquals("value", harness.selectRow(KEY_A.ns, KEY_A.id).executeAsOneOrNull())
            val status = assertNotNull(bookkeeper.status(KEY_A))
            assertEquals(42L, status.meta?.writtenAtEpochMillis)
            assertEquals("v1", status.meta?.etag)
        }
    }

    @Test
    fun withTransaction_launchedChildCannotEscapeDriverLease() = runTest {
        withHarness { harness ->
            val sourceOfTruth = sqlDelightTestSot<SqlTestKey, String>(harness)
            val releaseChild = CompletableDeferred<Unit>()
            var child: Job? = null

            sourceOfTruth.withTransaction {
                child =
                    CoroutineScope(currentCoroutineContext()).launch(
                        start = CoroutineStart.UNDISPATCHED,
                    ) {
                        releaseChild.await()
                        sourceOfTruth.write(KEY_A, "escaped")
                    }
            }

            releaseChild.complete(Unit)
            child!!.join()
            assertValueAndSidecarAbsent(harness, KEY_A)
        }
    }

    @Test
    fun cancellationDuringMutation_isExceptionAtomic() = runTest {
        withHarness { harness ->
            val sourceOfTruth =
                sourceOfTruth(harness) { key, value ->
                    harness.upsertRow(key.ns, key.id, value)
                    throw CancellationException("injected cancellation")
                }

            val failure =
                assertFailsWith<CancellationException> {
                    sourceOfTruth.write(KEY_A, "value")
                }

            assertEquals("injected cancellation", failure.message)
            assertValueAndSidecarAbsent(harness, KEY_A)
        }
    }

    private fun sourceOfTruth(
        harness: SqlHarness,
        writeRow: (SqlTestKey, String) -> Unit,
    ): SqlDelightSourceOfTruth<SqlTestKey, String> =
        SqlDelightSourceOfTruth(
            driver = harness.driver,
            transacter = harness.transacter,
            readQuery = { key -> harness.selectRow(key.ns, key.id) },
            writeRow = writeRow,
            deleteRow = { key -> harness.deleteRow(key.ns, key.id) },
            deleteNamespaceRows = { namespace -> harness.deleteNamespace(namespace.value) },
            deleteAllRows = harness::deleteAll,
        )

    private fun assertValueAndSidecarAbsent(
        harness: SqlHarness,
        key: SqlTestKey,
    ) {
        assertNull(harness.selectRow(key.ns, key.id).executeAsOneOrNull())
        assertNull(harness.metaRow(key.ns, key.id))
    }

    private suspend fun <R> withHarness(block: suspend (SqlHarness) -> R): R {
        val harness = freshHarness()
        return try {
            block(harness)
        } finally {
            harness.driver.close()
        }
    }

    private class InjectedFailure(message: String) : RuntimeException(message)

    private companion object {
        val KEY_A = SqlTestKey(ns = "users", id = "a")
        const val SCRATCH_ID = "journal-a"

        const val FAIL_META_INSERT_TRIGGER =
            """CREATE TRIGGER store6_test_fail
               BEFORE INSERT ON store6_meta
               BEGIN
                 SELECT RAISE(ABORT, 'injected');
               END"""

        const val SUSPENDING_BLOCK_MESSAGE =
            "withTransaction block suspended while executing against a synchronous SQLDelight " +
                "driver for this store. Keep the block to synchronous same-database statements " +
                "(this adapter's write/delete and other SQLDelight statements); move asynchronous " +
                "work outside withTransaction."
    }
}
