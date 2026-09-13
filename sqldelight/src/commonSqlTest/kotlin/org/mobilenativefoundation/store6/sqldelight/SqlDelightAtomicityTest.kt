@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.sqldelight

import app.cash.sqldelight.Transacter
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
import org.mobilenativefoundation.store6.testing.TestStoreMeta
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun callerCancellationAfterRowMutation_returnsNormallyAndNotifiesReader() = runTest {
        withHarness { harness ->
            lateinit var callerJob: Job
            var completion: Result<Unit>? = null
            val sourceOfTruth =
                sourceOfTruth(harness) { key, value ->
                    harness.upsertRow(key.ns, key.id, value)
                    callerJob.cancel(CancellationException("cancelled after row mutation"))
                }

            sourceOfTruth.reader(KEY_A).test {
                assertNull(awaitItem())
                val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                    callerJob = currentCoroutineContext()[Job]!!
                    completion = runCatching { sourceOfTruth.write(KEY_A, "committed") }
                }
                caller.join()

                assertTrue(caller.isCancelled)
                assertNotNull(completion).getOrThrow()
                assertEquals("committed", harness.selectRow(KEY_A.ns, KEY_A.id).executeAsOneOrNull())
                assertNotNull(harness.metaRow(KEY_A.ns, KEY_A.id))
                assertEquals("committed", awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
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

            sourceOfTruth.reader(KEY_A).test {
                assertNull(awaitItem())
                val failure =
                    assertFailsWith<CancellationException> {
                        sourceOfTruth.write(KEY_A, "value")
                    }

                assertEquals("injected cancellation", failure.message)
                assertValueAndSidecarAbsent(harness, KEY_A)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun delete_callerCancellationAfterRowMutation_returnsNormallyAndNotifiesReader() = runTest {
        assertMutationCancellation(SourceMutation.Delete)
    }

    @Test
    fun deleteNamespace_callerCancellationAfterRowMutation_returnsNormallyAndNotifiesReader() = runTest {
        assertMutationCancellation(SourceMutation.DeleteNamespace)
    }

    @Test
    fun deleteAll_callerCancellationAfterRowMutation_returnsNormallyAndNotifiesReader() = runTest {
        assertMutationCancellation(SourceMutation.DeleteAll)
    }

    @Test
    fun write_callerCancellationAtCommit_returnsNormallyAndNotifiesReader() = runTest {
        TransactionBoundary.entries.forEach { assertMutationCancellation(SourceMutation.Write, it) }
    }

    @Test
    fun delete_callerCancellationAtCommit_returnsNormallyAndNotifiesReader() = runTest {
        TransactionBoundary.entries.forEach { assertMutationCancellation(SourceMutation.Delete, it) }
    }

    @Test
    fun deleteNamespace_callerCancellationAtCommit_returnsNormallyAndNotifiesReader() = runTest {
        TransactionBoundary.entries.forEach { assertMutationCancellation(SourceMutation.DeleteNamespace, it) }
    }

    @Test
    fun deleteAll_callerCancellationAtCommit_returnsNormallyAndNotifiesReader() = runTest {
        TransactionBoundary.entries.forEach { assertMutationCancellation(SourceMutation.DeleteAll, it) }
    }

    @Test
    fun withTransaction_callerCancellationBeforeCommit_returnsNormallyAndNotifiesReader() = runTest {
        assertTransactionCancellation(TransactionBoundary.BeforeCommit)
    }

    @Test
    fun withTransaction_callerCancellationAfterCommit_returnsNormallyAndNotifiesReader() = runTest {
        assertTransactionCancellation(TransactionBoundary.AfterCommit)
    }

    @Test
    fun withTransaction_callerCancellationInsideWrite_keepsNestedCallsReentrant() = runTest {
        withHarness { harness ->
            lateinit var callerJob: Job
            val sourceOfTruth = sourceOfTruth(harness) { key, value ->
                harness.upsertRow(key.ns, key.id, value)
                callerJob.cancel()
            }
            val bookkeeper = SqlDelightBookkeeper(harness.driver, harness.transacter)
            val observed = observeMutationCall { job ->
                callerJob = job
                sourceOfTruth.withTransaction {
                    sourceOfTruth.write(KEY_A, "first")
                    bookkeeper.recordSuccess(KEY_A, TestStoreMeta(42L, "v1"))
                    assertEquals("v1", bookkeeper.status(KEY_A)?.meta?.etag)
                    sourceOfTruth.withTransaction {
                        sourceOfTruth.write(KEY_B, "second")
                    }
                }
            }

            assertTrue(observed.callerCancelled)
            observed.completion.getOrThrow()
            assertEquals("first", harness.selectRow(KEY_A.ns, KEY_A.id).executeAsOneOrNull())
            assertEquals("second", harness.selectRow(KEY_B.ns, KEY_B.id).executeAsOneOrNull())
            assertEquals("v1", bookkeeper.status(KEY_A)?.meta?.etag)
        }
    }

    @Test
    fun withTransaction_explicitCancellation_rollsBackAndDoesNotNotifyReader() = runTest {
        withHarness { harness ->
            val sourceOfTruth = sqlDelightTestSot<SqlTestKey, String>(harness)
            sourceOfTruth.reader(KEY_A).test {
                assertNull(awaitItem())
                assertFailsWith<CancellationException> {
                    sourceOfTruth.withTransaction {
                        sourceOfTruth.write(KEY_A, "partial")
                        harness.upsertScratch(SCRATCH_ID, "partial")
                        throw CancellationException("transaction cancelled")
                    }
                }

                assertValueAndSidecarAbsent(harness, KEY_A)
                assertNull(harness.selectScratch(SCRATCH_ID))
                expectNoEvents()
                sourceOfTruth.write(KEY_A, "recovered")
                assertEquals("recovered", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun deletes_explicitCallbackCancellation_rollBackAndDoNotNotifyReader() = runTest {
        SourceMutation.entries.filter { it != SourceMutation.Write }.forEach { mutation ->
            withHarness { harness ->
                var callback: (() -> Unit)? = null
                val sourceOfTruth = sourceOfTruth(harness, onRowMutation = { callback?.invoke() })
                val keys = listOf(KEY_A, KEY_B, KEY_OTHER_NAMESPACE)
                keys.forEach { sourceOfTruth.write(it, "before") }
                val beforeMeta = keys.map { harness.metaRow(it.ns, it.id) }
                callback = { throw CancellationException("delete callback cancelled") }

                sourceOfTruth.reader(KEY_A).test {
                    assertEquals("before", awaitItem())
                    assertFailsWith<CancellationException> { mutation.apply(sourceOfTruth) }

                    keys.forEach {
                        assertEquals("before", harness.selectRow(it.ns, it.id).executeAsOneOrNull())
                    }
                    assertEquals(beforeMeta, keys.map { harness.metaRow(it.ns, it.id) })
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    }

    @Test
    fun cancellationBeforeAdmission_doesNotMutateOrNotifyReader() = runTest {
        withHarness { harness ->
            var callbackEntered = false
            val sourceOfTruth = sourceOfTruth(harness) { key, value ->
                callbackEntered = true
                harness.upsertRow(key.ns, key.id, value)
            }
            sourceOfTruth.reader(KEY_A).test {
                assertNull(awaitItem())
                val observed = observeMutationCall { job ->
                    job.cancel()
                    sourceOfTruth.write(KEY_A, "cancelled")
                }

                assertTrue(observed.callerCancelled)
                assertTrue(observed.completion.exceptionOrNull() is CancellationException)
                assertEquals(false, callbackEntered)
                assertValueAndSidecarAbsent(harness, KEY_A)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    private suspend fun assertMutationCancellation(
        mutation: SourceMutation,
        boundary: TransactionBoundary? = null,
    ) {
        withHarness { harness ->
            val faults = TransactionBoundaryFaults(harness.transacter)
            var cancelAfterRow: (() -> Unit)? = null
            val sourceOfTruth = sourceOfTruth(
                harness,
                transacter = faults,
                onRowMutation = { cancelAfterRow?.invoke() },
            )
            sourceOfTruth.write(KEY_A, "before")
            sourceOfTruth.write(KEY_B, "same namespace")
            sourceOfTruth.write(KEY_OTHER_NAMESPACE, "other namespace")

            sourceOfTruth.reader(KEY_A).test {
                assertEquals("before", awaitItem())
                val observed = observeMutationCall { job ->
                    if (boundary == null) {
                        cancelAfterRow = { job.cancel() }
                    } else {
                        faults.arm(boundary) { job.cancel() }
                    }
                    mutation.apply(sourceOfTruth)
                }

                assertTrue(observed.callerCancelled, "$mutation at $boundary")
                observed.completion.getOrThrow()
                val expected = if (mutation == SourceMutation.Write) "after" else null
                assertEquals(expected, harness.selectRow(KEY_A.ns, KEY_A.id).executeAsOneOrNull())
                assertEquals(expected, awaitItem())
                if (expected == null) assertNull(harness.metaRow(KEY_A.ns, KEY_A.id))
                else assertNotNull(harness.metaRow(KEY_A.ns, KEY_A.id))
                val sameNamespace =
                    if (mutation == SourceMutation.DeleteNamespace || mutation == SourceMutation.DeleteAll) null
                    else "same namespace"
                assertEquals(sameNamespace, harness.selectRow(KEY_B.ns, KEY_B.id).executeAsOneOrNull())
                val otherNamespace = if (mutation == SourceMutation.DeleteAll) null else "other namespace"
                assertEquals(
                    otherNamespace,
                    harness.selectRow(KEY_OTHER_NAMESPACE.ns, KEY_OTHER_NAMESPACE.id).executeAsOneOrNull(),
                )
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    private suspend fun assertTransactionCancellation(boundary: TransactionBoundary) {
        withHarness { harness ->
            val faults = TransactionBoundaryFaults(harness.transacter)
            val sourceOfTruth = sourceOfTruth(harness, transacter = faults)
            sourceOfTruth.reader(KEY_A).test {
                assertNull(awaitItem())
                val observed = observeMutationCall { job ->
                    faults.arm(boundary) { job.cancel() }
                    val result = sourceOfTruth.withTransaction {
                        sourceOfTruth.write(KEY_A, "committed")
                        harness.upsertScratch(SCRATCH_ID, "retired")
                        "transaction result"
                    }
                    assertEquals("transaction result", result)
                }

                assertTrue(observed.callerCancelled)
                observed.completion.getOrThrow()
                assertEquals("committed", harness.selectRow(KEY_A.ns, KEY_A.id).executeAsOneOrNull())
                assertNotNull(harness.metaRow(KEY_A.ns, KEY_A.id))
                assertEquals("retired", harness.selectScratch(SCRATCH_ID))
                assertEquals("committed", awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    private enum class SourceMutation {
        Write,
        Delete,
        DeleteNamespace,
        DeleteAll,
        ;

        suspend fun apply(sourceOfTruth: SqlDelightSourceOfTruth<SqlTestKey, String>) {
            when (this) {
                Write -> sourceOfTruth.write(KEY_A, "after")
                Delete -> sourceOfTruth.delete(KEY_A)
                DeleteNamespace -> sourceOfTruth.deleteNamespace(KEY_A.namespace)
                DeleteAll -> sourceOfTruth.deleteAll()
            }
        }
    }

    private fun sourceOfTruth(
        harness: SqlHarness,
        transacter: Transacter = harness.transacter,
        onRowMutation: () -> Unit = {},
        writeRow: (SqlTestKey, String) -> Unit = { key, value ->
            harness.upsertRow(key.ns, key.id, value)
        },
    ): SqlDelightSourceOfTruth<SqlTestKey, String> =
        SqlDelightSourceOfTruth(
            driver = harness.driver,
            transacter = transacter,
            readQuery = { key -> harness.selectRow(key.ns, key.id) },
            writeRow = { key, value ->
                writeRow(key, value)
                onRowMutation()
            },
            deleteRow = { key ->
                harness.deleteRow(key.ns, key.id)
                onRowMutation()
            },
            deleteNamespaceRows = { namespace ->
                harness.deleteNamespace(namespace.value)
                onRowMutation()
            },
            deleteAllRows = {
                harness.deleteAll()
                onRowMutation()
            },
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
        val KEY_B = SqlTestKey(ns = "users", id = "b")
        val KEY_OTHER_NAMESPACE = SqlTestKey(ns = "other", id = "a")
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
