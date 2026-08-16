@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.testing

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conformance kit for [Bookkeeper] implementations. Extend it in your test source set and run your
 * tests: every inherited @Test member executes on every target you compile.
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
