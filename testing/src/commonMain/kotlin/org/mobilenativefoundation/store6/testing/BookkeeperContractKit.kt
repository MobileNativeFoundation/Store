@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.testing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Conformance kit for [Bookkeeper] implementations. Extend it in your test source set and run your
 * tests: every inherited @Test member executes on every target you compile.
 * Checks without `@Test` require an explicit test wrapper in the consumer. Fault checks also
 * require an adapter fixture wired into its actual storage boundary.
 *
 * ```
 * class MyBookkeeperContractTest : BookkeeperContractKit() {
 *     override fun createBookkeeper() = MyBookkeeper()
 * }
 * ```
 */
@ExperimentalStoreApi
public abstract class BookkeeperContractKit {
    /** Creates a fresh bookkeeper for one contract test. */
    public abstract fun createBookkeeper(): Bookkeeper

    @Test
    public fun identityDerivation_equalCanonicalPair_sharesRecords(): TestResult =
        runTest {
            val bookkeeper = createBookkeeper()
            val first = FirstKey(namespace = StoreNamespace("shared"), id = "key")
            val equal = SecondKey(namespaceName = "shared", idParts = listOf("k", "ey"))
            val meta = TestStoreMeta(writtenAtEpochMillis = 1L, etag = "v1")

            bookkeeper.recordSuccess(first, meta)
            val sharedMeta = requireNotNull(requireNotNull(bookkeeper.status(equal)).meta)
            assertEquals(meta.writtenAtEpochMillis, sharedMeta.writtenAtEpochMillis)
            assertEquals(meta.etag, sharedMeta.etag)

            bookkeeper.markStale(equal)
            assertTrue(requireNotNull(bookkeeper.status(first)).durablyStale)

            bookkeeper.forget(first)
            assertNull(bookkeeper.status(equal))
        }

    @Test
    public fun identityDerivation_differentCanonicalId_isolated(): TestResult = runTest {
        val bookkeeper = createBookkeeper()
        val namespace = StoreNamespace("shared")
        val first = FirstKey(namespace = namespace, id = "first")
        val second = FirstKey(namespace = namespace, id = "second")
        val sameIdOtherNamespace =
            FirstKey(namespace = StoreNamespace("other"), id = "first")
        val meta = TestStoreMeta(writtenAtEpochMillis = 1L, etag = null)

        bookkeeper.recordSuccess(first, meta)
        assertNull(bookkeeper.status(sameIdOtherNamespace))
        bookkeeper.markStale(second)

        val firstStatus = requireNotNull(bookkeeper.status(first))
        val firstMeta = requireNotNull(firstStatus.meta)
        assertEquals(meta.writtenAtEpochMillis, firstMeta.writtenAtEpochMillis)
        assertEquals(meta.etag, firstMeta.etag)
        assertFalse(firstStatus.durablyStale)
        val secondStatus = requireNotNull(bookkeeper.status(second))
        assertNull(secondStatus.meta)
        assertTrue(secondStatus.durablyStale)
    }

    @Test
    public fun failureOnlyRecord_isNotDurablyStale(): TestResult = runTest {
        val bookkeeper = createBookkeeper()
        val key = FirstKey(namespace = StoreNamespace("failure"), id = "key")

        bookkeeper.recordFailure(key, atEpochMillis = 10L)

        val status = requireNotNull(bookkeeper.status(key))
        assertNull(status.meta)
        assertNull(status.lastSuccessSequence)
        assertEquals(10L, status.lastFailureAtEpochMillis)
        assertEquals(1, status.consecutiveFailures)
        assertFalse(status.durablyStale)
    }

    @Test
    public fun namespaceWatermark_thenLaterSuccess_clearsDurableStaleness(): TestResult = runTest {
        val bookkeeper = createBookkeeper()
        val key = FirstKey(namespace = StoreNamespace("watermarked"), id = "key")
        val meta = TestStoreMeta(writtenAtEpochMillis = 20L, etag = "fresh")

        bookkeeper.advanceStaleWatermark(StoreNamespace(key.namespace.value))
        assertTrue(requireNotNull(bookkeeper.status(key)).durablyStale)

        bookkeeper.recordSuccess(key, meta)

        val status = requireNotNull(bookkeeper.status(key))
        val statusMeta = requireNotNull(status.meta)
        assertEquals(meta.writtenAtEpochMillis, statusMeta.writtenAtEpochMillis)
        assertEquals(meta.etag, statusMeta.etag)
        assertFalse(status.durablyStale)
    }

    @Test
    public fun watermarkOnlyKey_reportsDurablyStale(): TestResult = runTest {
        val bookkeeper = createBookkeeper()
        val namespace = StoreNamespace("watermarked")
        val key = FirstKey(namespace = namespace, id = "never-seen")

        bookkeeper.advanceStaleWatermark(StoreNamespace(key.namespace.value))

        val status = requireNotNull(bookkeeper.status(key))
        assertNull(status.meta)
        assertNull(status.lastSuccessSequence)
        assertNull(status.lastFailureAtEpochMillis)
        assertEquals(0, status.consecutiveFailures)
        assertTrue(status.durablyStale)
    }

    @Test
    public fun recordSuccess_clearsFailureCountAndTimestamp(): TestResult = runTest {
        val bookkeeper = createBookkeeper()
        val key = FirstKey(namespace = StoreNamespace("failure"), id = "key")
        val meta = TestStoreMeta(writtenAtEpochMillis = 30L, etag = "recovered")
        bookkeeper.recordFailure(key, atEpochMillis = 10L)
        bookkeeper.recordFailure(key, atEpochMillis = 20L)

        bookkeeper.recordSuccess(key, meta)

        val status = requireNotNull(bookkeeper.status(key))
        val statusMeta = requireNotNull(status.meta)
        assertEquals(meta.writtenAtEpochMillis, statusMeta.writtenAtEpochMillis)
        assertEquals(meta.etag, statusMeta.etag)
        assertNull(status.lastFailureAtEpochMillis)
        assertEquals(0, status.consecutiveFailures)
    }

    /** Invoke from an explicit test wrapper to verify global coverage without existing records. */
    public fun globalWatermark_coversNeverSeenKeys(): TestResult = runTest {
        val bookkeeper = createBookkeeper()
        bookkeeper.advanceGlobalStaleWatermark()

        listOf(key("first", "unseen"), key("second", "unseen")).forEach {
            assertWatermarkOnly(requireNotNull(bookkeeper.status(it)))
        }
    }

    /** A success clears earlier marks for its key; later marks and other keys retain authority. */
    public fun laterSuccess_clearsOnlyEarlierStalenessForItsKey(): TestResult = runTest {
        val bookkeeper = createBookkeeper()
        val first = key("shared", "first")
        val sibling = key("shared", "sibling")
        val other = key("other", "first")
        bookkeeper.markStale(first)
        bookkeeper.advanceStaleWatermark(first.namespace)
        bookkeeper.advanceGlobalStaleWatermark()
        bookkeeper.recordSuccess(first, TestStoreMeta(10L, "first"))

        assertFalse(requireNotNull(bookkeeper.status(first)).durablyStale)
        assertWatermarkOnly(requireNotNull(bookkeeper.status(sibling)))
        assertWatermarkOnly(requireNotNull(bookkeeper.status(other)))

        bookkeeper.markStale(first)
        assertTrue(requireNotNull(bookkeeper.status(first)).durablyStale)
        bookkeeper.recordSuccess(first, TestStoreMeta(20L, "second"))
        bookkeeper.advanceStaleWatermark(first.namespace)
        assertTrue(requireNotNull(bookkeeper.status(first)).durablyStale)
        bookkeeper.recordSuccess(first, TestStoreMeta(30L, "third"))
        bookkeeper.advanceGlobalStaleWatermark()
        assertTrue(requireNotNull(bookkeeper.status(first)).durablyStale)
    }

    /** Success sequence values account for intervening marks and watermarks across namespaces. */
    public fun marksAndSuccesses_shareOneMonotoneSequence(): TestResult = runTest {
        val bookkeeper = createBookkeeper()
        val first = key("first", "key")
        val other = key("other", "key")
        bookkeeper.recordSuccess(first, TestStoreMeta(10L, null))
        val initial = requireNotNull(requireNotNull(bookkeeper.status(first)).lastSuccessSequence)
        bookkeeper.markStale(first)
        bookkeeper.advanceStaleWatermark(other.namespace)
        bookkeeper.advanceGlobalStaleWatermark()
        bookkeeper.recordSuccess(other, TestStoreMeta(5L, null))
        val later = requireNotNull(requireNotNull(bookkeeper.status(other)).lastSuccessSequence)

        assertTrue(later >= initial + 4L)
        assertTrue(requireNotNull(bookkeeper.status(first)).durablyStale)
        assertFalse(requireNotNull(bookkeeper.status(other)).durablyStale)
        bookkeeper.forgetAll()
        bookkeeper.recordSuccess(first, TestStoreMeta(1L, null))
        assertTrue(requireNotNull(requireNotNull(bookkeeper.status(first)).lastSuccessSequence) > later)
    }

    /** Per-key forgetting removes success, failure and stale-mark fields while retaining coverage. */
    public fun forget_removesRecordAndPreservesWatermarks(): TestResult = runTest {
        val bookkeeper = createBookkeeper()
        val covered = key("covered", "key")
        val sibling = key("covered", "sibling")
        val uncovered = key("uncovered", "key")
        bookkeeper.advanceStaleWatermark(covered.namespace)
        seedRecord(bookkeeper, covered)
        seedRecord(bookkeeper, sibling)
        seedRecord(bookkeeper, uncovered)

        bookkeeper.forget(covered)
        assertWatermarkOnly(requireNotNull(bookkeeper.status(covered)))
        assertEquals("retained", requireNotNull(bookkeeper.status(sibling)).meta?.etag)
        bookkeeper.forget(uncovered)
        assertNull(bookkeeper.status(uncovered))

        bookkeeper.advanceGlobalStaleWatermark()
        seedRecord(bookkeeper, uncovered)
        bookkeeper.forget(uncovered)
        assertWatermarkOnly(requireNotNull(bookkeeper.status(uncovered)))
    }

    /** Namespace forgetting isolates records by namespace and retains namespace and global coverage. */
    public fun forgetNamespace_removesOnlyMatchingRecordsAndPreservesWatermarks(): TestResult = runTest {
        val bookkeeper = createBookkeeper()
        val first = key("covered", "first")
        val second = key("covered", "second")
        val other = key("other", "first")
        bookkeeper.advanceStaleWatermark(first.namespace)
        listOf(first, second, other).forEach { seedRecord(bookkeeper, it) }

        bookkeeper.forgetNamespace(StoreNamespace(first.namespace.value))
        listOf(first, second, key("covered", "unseen")).forEach {
            assertWatermarkOnly(requireNotNull(bookkeeper.status(it)))
        }
        assertEquals("retained", requireNotNull(bookkeeper.status(other)).meta?.etag)
        bookkeeper.forgetNamespace(other.namespace)
        assertNull(bookkeeper.status(other))

        bookkeeper.advanceGlobalStaleWatermark()
        seedRecord(bookkeeper, other)
        bookkeeper.forgetNamespace(other.namespace)
        assertWatermarkOnly(requireNotNull(bookkeeper.status(other)))
    }

    /** Global forgetting removes records in every namespace without clearing either watermark. */
    public fun forgetAll_removesRecordsAndPreservesWatermarks(): TestResult = runTest {
        val bookkeeper = createBookkeeper()
        val first = key("covered", "first")
        val other = key("other", "first")
        bookkeeper.advanceStaleWatermark(first.namespace)
        listOf(first, other).forEach { seedRecord(bookkeeper, it) }
        bookkeeper.forgetAll()
        assertWatermarkOnly(requireNotNull(bookkeeper.status(first)))
        assertNull(bookkeeper.status(other))

        bookkeeper.advanceGlobalStaleWatermark()
        listOf(first, other).forEach { seedRecord(bookkeeper, it) }
        bookkeeper.forgetAll()
        listOf(first, other, key("unseen", "key")).forEach {
            assertWatermarkOnly(requireNotNull(bookkeeper.status(it)))
        }
        bookkeeper.recordSuccess(first, TestStoreMeta(20L, "recovered"))
        assertFalse(requireNotNull(bookkeeper.status(first)).durablyStale)
        assertWatermarkOnly(requireNotNull(bookkeeper.status(other)))
    }

    /** Storage read failures must throw, preserving both missing and known-stale status on recovery. */
    public fun status_storageFailure_propagatesAndRecovers(
        createFixture: () -> BookkeeperStatusFaultFixture,
    ): TestResult = runTest {
        listOf(false, true).forEach { stale ->
            val fixture = createFixture()
            try {
                val bookkeeper = fixture.bookkeeper
                val key = key("status", "key")
                if (stale) bookkeeper.markStale(key)
                val before = snapshot(bookkeeper.status(key))
                val failure = IllegalStateException("status storage failed")
                var reached = false
                fixture.onNextStatusRead {
                    reached = true
                    throw failure
                }
                val observed = observeMutationCall { bookkeeper.status(key) }
                assertTrue(reached, "The actual status storage read was not reached")
                assertThrownFailure(failure, observed.completion.exceptionOrNull())
                assertEquals(before, snapshot(bookkeeper.status(key)))
            } finally {
                fixture.close()
            }
        }
    }

    /** Cooperative caller cancellation propagates through status and leaves stale authority intact. */
    public fun status_externalCancellation_propagatesAndRecovers(
        createFixture: () -> BookkeeperStatusFaultFixture,
    ): TestResult = runTest {
        val fixture = createFixture()
        try {
            val bookkeeper = fixture.bookkeeper
            val key = key("status", "key")
            bookkeeper.markStale(key)
            val before = snapshot(bookkeeper.status(key))
            var reached = false
            val observed = observeMutationCall { caller ->
                fixture.onNextStatusRead {
                    reached = true
                    caller.cancel()
                    throw CancellationException("status caller cancelled")
                }
                bookkeeper.status(key)
            }
            assertTrue(reached, "The actual status storage read was not reached")
            assertTrue(observed.callerCancelled)
            assertIs<CancellationException>(observed.completion.exceptionOrNull())
            assertEquals(before, snapshot(bookkeeper.status(key)))
        } finally {
            fixture.close()
        }
    }

    /** Each fallible maintenance mutation must leave status unchanged after a thrown storage failure. */
    public fun maintenance_throwBeforeCommit_preservesStatus(
        createFixture: () -> BookkeeperFaultFixture,
    ): TestResult = runTest {
        MaintenanceMutation.entries.forEach { mutation ->
            listOf(IllegalStateException("storage failed"), CancellationException("storage cancelled"), AssertionError("storage error")).forEach { failure ->
                val fixture = createFixture()
                try {
                    assertMaintenanceOutcome(fixture, mutation, MutationFaultPoint.BeforeCommit, failure)
                } finally {
                    fixture.close()
                }
            }
        }
    }

    /** External cancellation checks maintenance completion at both sides of the durable commit. */
    public fun maintenance_externalCancellationAtCommit_obeysOutcome(
        createFixture: () -> BookkeeperFaultFixture,
        points: Set<MutationFaultPoint> = MutationFaultPoint.entries.toSet(),
    ): TestResult = runTest {
        require(points.isNotEmpty())
        MaintenanceMutation.entries.forEach { mutation ->
            points.forEach { point ->
                val fixture = createFixture()
                try {
                    assertMaintenanceOutcome(fixture, mutation, point, null)
                } finally {
                    fixture.close()
                }
            }
        }
    }

    private suspend fun assertMaintenanceOutcome(
        fixture: BookkeeperFaultFixture,
        mutation: MaintenanceMutation,
        point: MutationFaultPoint,
        failure: Throwable?,
    ) {
        val bookkeeper = fixture.bookkeeper
        val keys = listOf(key("first", "a"), key("first", "b"), key("other", "a"))
        bookkeeper.advanceGlobalStaleWatermark()
        keys.forEach {
            bookkeeper.recordSuccess(it, TestStoreMeta(10L, "before"))
            bookkeeper.recordFailure(it, 20L)
        }
        val before = keys.map { assertNotNull(snapshot(bookkeeper.status(it))) }
        var reached = false
        val observed = observeMutationCall { caller ->
            fixture.faults.arm(point) {
                reached = true
                if (failure != null) throw failure
                caller.cancel()
            }
            when (mutation) {
                MaintenanceMutation.MarkStale -> bookkeeper.markStale(keys[0])
                MaintenanceMutation.NamespaceWatermark -> bookkeeper.advanceStaleWatermark(keys[0].namespace)
                MaintenanceMutation.GlobalWatermark -> bookkeeper.advanceGlobalStaleWatermark()
                MaintenanceMutation.ForgetNamespace -> bookkeeper.forgetNamespace(keys[0].namespace)
                MaintenanceMutation.ForgetAll -> bookkeeper.forgetAll()
            }
        }
        assertTrue(reached, "Storage fault did not fire for $mutation at $point")
        if (failure != null) {
            assertThrownFailure(failure, observed.completion.exceptionOrNull())
        } else {
            assertTrue(observed.callerCancelled)
            observed.completion.exceptionOrNull()?.let { assertIs<CancellationException>(it) }
            if (point == MutationFaultPoint.AfterCommit) observed.completion.getOrThrow()
        }
        val expected = if (observed.completion.isFailure) before else before.mapIndexed { index, status ->
            when (mutation) {
                MaintenanceMutation.MarkStale -> if (index == 0) status.copy(durablyStale = true) else status
                MaintenanceMutation.NamespaceWatermark -> if (index < 2) status.copy(durablyStale = true) else status
                MaintenanceMutation.GlobalWatermark -> status.copy(durablyStale = true)
                MaintenanceMutation.ForgetNamespace -> if (index < 2) WATERMARK_ONLY else status
                MaintenanceMutation.ForgetAll -> WATERMARK_ONLY
            }
        }
        assertEquals(expected, keys.map { snapshot(bookkeeper.status(it)) })
        assertWatermarkOnly(requireNotNull(bookkeeper.status(key("unseen", "key"))))
    }

    private data class StatusSnapshot(
        val writtenAtEpochMillis: Long?,
        val etag: String?,
        val lastSuccessSequence: Long?,
        val lastFailureAtEpochMillis: Long?,
        val consecutiveFailures: Int,
        val durablyStale: Boolean,
    )

    private fun snapshot(status: KeyStatus?): StatusSnapshot? = status?.let {
        StatusSnapshot(
            it.meta?.writtenAtEpochMillis,
            it.meta?.etag,
            it.lastSuccessSequence,
            it.lastFailureAtEpochMillis,
            it.consecutiveFailures,
            it.durablyStale,
        )
    }

    private fun assertThrownFailure(injected: Throwable, thrown: Throwable?) {
        assertNotNull(thrown)
        if (injected is CancellationException) assertIs<CancellationException>(thrown)
        else assertFalse(thrown is CancellationException)
    }

    private enum class MaintenanceMutation { MarkStale, NamespaceWatermark, GlobalWatermark, ForgetNamespace, ForgetAll }

    private companion object {
        val WATERMARK_ONLY = StatusSnapshot(null, null, null, null, 0, true)
    }

    private fun key(namespace: String, id: String): StoreKey = FirstKey(StoreNamespace(namespace), id)

    private suspend fun seedRecord(bookkeeper: Bookkeeper, key: StoreKey) {
        bookkeeper.recordSuccess(key, TestStoreMeta(10L, "retained"))
        bookkeeper.recordFailure(key, 20L)
        bookkeeper.markStale(key)
    }

    private fun assertWatermarkOnly(status: KeyStatus) {
        assertNull(status.meta)
        assertNull(status.lastSuccessSequence)
        assertNull(status.lastFailureAtEpochMillis)
        assertEquals(0, status.consecutiveFailures)
        assertTrue(status.durablyStale)
    }

    private class FirstKey(
        override val namespace: StoreNamespace,
        private val id: String,
    ) : StoreKey {
        override fun canonicalId(): String = id
    }

    private class SecondKey(
        private val namespaceName: String,
        private val idParts: List<String>,
    ) : StoreKey {
        override val namespace: StoreNamespace
            get() = StoreNamespace(namespaceName)

        override fun canonicalId(): String = idParts.joinToString(separator = "")
    }

    private class TestStoreMeta(
        override val writtenAtEpochMillis: Long,
        override val etag: String?,
    ) : StoreMeta
}
