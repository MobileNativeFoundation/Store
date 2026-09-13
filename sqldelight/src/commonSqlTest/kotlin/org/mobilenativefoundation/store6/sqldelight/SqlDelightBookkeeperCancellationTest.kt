@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.sqldelight

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.testing.TestStoreMeta
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class SqlDelightBookkeeperCancellationTest {
    @Test
    fun recordSuccess_callerCancellationAtCommit_returnsNormally() = runTest {
        assertCommittedCancellation(BookkeeperMutation.RecordSuccess)
    }

    @Test
    fun recordFailure_callerCancellationAtCommit_returnsNormally() = runTest {
        assertCommittedCancellation(BookkeeperMutation.RecordFailure)
    }

    @Test
    fun forget_callerCancellationAtCommit_returnsNormally() = runTest {
        assertCommittedCancellation(BookkeeperMutation.Forget)
    }

    @Test
    fun markStale_callerCancellationAtCommit_returnsNormally() = runTest {
        assertCommittedCancellation(BookkeeperMutation.MarkStale)
    }

    @Test
    fun advanceStaleWatermark_callerCancellationAtCommit_returnsNormally() = runTest {
        assertCommittedCancellation(BookkeeperMutation.AdvanceNamespaceWatermark)
    }

    @Test
    fun advanceGlobalStaleWatermark_callerCancellationAtCommit_returnsNormally() = runTest {
        assertCommittedCancellation(BookkeeperMutation.AdvanceGlobalWatermark)
    }

    @Test
    fun forgetNamespace_callerCancellationAtCommit_returnsNormally() = runTest {
        assertCommittedCancellation(BookkeeperMutation.ForgetNamespace)
    }

    @Test
    fun forgetAll_callerCancellationAtCommit_returnsNormally() = runTest {
        assertCommittedCancellation(BookkeeperMutation.ForgetAll)
    }

    @Test
    fun maintenance_explicitCancellationBeforeCommit_rollsBack() = runTest {
        BookkeeperMutation.entries.forEach { mutation ->
            withBookkeeper { harness, bookkeeper, faults ->
                seed(bookkeeper)
                val before = KEYS.map { harness.metaRow(it.ns, it.id) }
                val failure = CancellationException("cancelled before commit")
                faults.arm(TransactionBoundary.BeforeCommit) { throw failure }

                val observed = observeMutationCall { mutation.apply(bookkeeper) }

                val actual = assertIs<CancellationException>(observed.completion.exceptionOrNull(), "$mutation")
                assertEquals(failure.message, actual.message, "$mutation")
                assertTrue(actual === failure || actual.cause === failure, "$mutation")
                assertEquals(before, KEYS.map { harness.metaRow(it.ns, it.id) }, "$mutation")
                assertNull(harness.watermark("ns:${KEY_A.ns}"))
                assertNull(harness.watermark("global"))
                bookkeeper.recordSuccess(KEY_A, TestStoreMeta(100L, "recovered"))
                assertEquals("recovered", bookkeeper.status(KEY_A)?.meta?.etag)
            }
        }
    }

    @Test
    fun maintenance_cancellationBeforeAdmission_doesNotApply() = runTest {
        BookkeeperMutation.entries.forEach { mutation ->
            withBookkeeper { harness, bookkeeper, _ ->
                seed(bookkeeper)
                val before = KEYS.map { harness.metaRow(it.ns, it.id) }

                val observed = observeMutationCall { job ->
                    job.cancel()
                    mutation.apply(bookkeeper)
                }

                assertTrue(observed.callerCancelled, "$mutation")
                assertTrue(observed.completion.exceptionOrNull() is CancellationException, "$mutation")
                assertEquals(before, KEYS.map { harness.metaRow(it.ns, it.id) }, "$mutation")
                assertNull(harness.watermark("ns:${KEY_A.ns}"))
                assertNull(harness.watermark("global"))
            }
        }
    }

    @Test
    fun status_callerCancellationAtCompletion_propagates() = runTest {
        withBookkeeper { _, bookkeeper, faults ->
            seed(bookkeeper)

            val observed = observeMutationCall { job ->
                faults.arm(TransactionBoundary.AfterCommit) { job.cancel() }
                bookkeeper.status(KEY_A)
            }

            assertTrue(observed.callerCancelled)
            assertTrue(observed.completion.exceptionOrNull() is CancellationException)
            assertEquals("original", bookkeeper.status(KEY_A)?.meta?.etag)
        }
    }

    private suspend fun assertCommittedCancellation(mutation: BookkeeperMutation) {
        TransactionBoundary.entries.forEach { boundary ->
            withBookkeeper { harness, bookkeeper, faults ->
                seed(bookkeeper)
                val before = KEYS.associateWith { harness.metaRow(it.ns, it.id) }

                val observed = observeMutationCall { job ->
                    faults.arm(boundary) { job.cancel() }
                    mutation.apply(bookkeeper)
                }

                assertTrue(observed.callerCancelled, "$mutation at $boundary")
                observed.completion.getOrThrow()
                when (mutation) {
                    BookkeeperMutation.RecordSuccess -> {
                        val status = assertNotNull(bookkeeper.status(KEY_A))
                        assertEquals(42L, status.meta?.writtenAtEpochMillis)
                        assertEquals("updated", status.meta?.etag)
                        assertTrue(status.lastSuccessSequence!! > before.getValue(KEY_A)!!.successSequence!!)
                    }
                    BookkeeperMutation.RecordFailure -> {
                        val status = assertNotNull(bookkeeper.status(KEY_A))
                        assertEquals(42L, status.lastFailureAtEpochMillis)
                        assertEquals(1, status.consecutiveFailures)
                    }
                    BookkeeperMutation.Forget -> assertNull(bookkeeper.status(KEY_A))
                    BookkeeperMutation.MarkStale -> assertTrue(assertNotNull(bookkeeper.status(KEY_A)).durablyStale)
                    BookkeeperMutation.AdvanceNamespaceWatermark -> {
                        assertTrue(assertNotNull(bookkeeper.status(KEY_A)).durablyStale)
                        assertTrue(assertNotNull(bookkeeper.status(KEY_B)).durablyStale)
                        assertNotNull(harness.watermark("ns:${KEY_A.ns}"))
                    }
                    BookkeeperMutation.AdvanceGlobalWatermark -> {
                        KEYS.forEach { assertTrue(assertNotNull(bookkeeper.status(it)).durablyStale) }
                        assertNotNull(harness.watermark("global"))
                    }
                    BookkeeperMutation.ForgetNamespace -> {
                        assertNull(bookkeeper.status(KEY_A))
                        assertNull(bookkeeper.status(KEY_B))
                    }
                    BookkeeperMutation.ForgetAll -> KEYS.forEach { assertNull(bookkeeper.status(it)) }
                }
                if (mutation != BookkeeperMutation.ForgetAll) {
                    assertEquals(before.getValue(KEY_OTHER_NAMESPACE), harness.metaRow(KEY_OTHER_NAMESPACE.ns, KEY_OTHER_NAMESPACE.id))
                }
                if (mutation != BookkeeperMutation.ForgetNamespace && mutation != BookkeeperMutation.ForgetAll) {
                    assertEquals(before.getValue(KEY_B), harness.metaRow(KEY_B.ns, KEY_B.id))
                }
            }
        }
    }

    private suspend fun seed(bookkeeper: SqlDelightBookkeeper) {
        KEYS.forEach { bookkeeper.recordSuccess(it, TestStoreMeta(1L, "original")) }
    }

    private suspend fun withBookkeeper(
        block: suspend (SqlHarness, SqlDelightBookkeeper, TransactionBoundaryFaults) -> Unit,
    ) {
        val harness = freshHarness()
        try {
            val faults = TransactionBoundaryFaults(harness.transacter)
            val bookkeeper = SqlDelightBookkeeper(harness.driver, faults)
            block(harness, bookkeeper, faults)
        } finally {
            harness.driver.close()
        }
    }

    private enum class BookkeeperMutation {
        RecordSuccess,
        RecordFailure,
        Forget,
        MarkStale,
        AdvanceNamespaceWatermark,
        AdvanceGlobalWatermark,
        ForgetNamespace,
        ForgetAll,
        ;

        suspend fun apply(bookkeeper: SqlDelightBookkeeper) {
            when (this) {
                RecordSuccess -> bookkeeper.recordSuccess(KEY_A, TestStoreMeta(42L, "updated"))
                RecordFailure -> bookkeeper.recordFailure(KEY_A, 42L)
                Forget -> bookkeeper.forget(KEY_A)
                MarkStale -> bookkeeper.markStale(KEY_A)
                AdvanceNamespaceWatermark -> bookkeeper.advanceStaleWatermark(KEY_A.namespace)
                AdvanceGlobalWatermark -> bookkeeper.advanceGlobalStaleWatermark()
                ForgetNamespace -> bookkeeper.forgetNamespace(KEY_A.namespace)
                ForgetAll -> bookkeeper.forgetAll()
            }
        }
    }

    private companion object {
        val KEY_A = SqlTestKey(ns = "users", id = "a")
        val KEY_B = SqlTestKey(ns = "users", id = "b")
        val KEY_OTHER_NAMESPACE = SqlTestKey(ns = "other", id = "a")
        val KEYS = listOf(KEY_A, KEY_B, KEY_OTHER_NAMESPACE)
    }
}
