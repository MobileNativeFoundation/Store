package org.mobilenativefoundation.store6.testing

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreNamespace
import kotlin.test.*

@OptIn(ExperimentalStoreApi::class)
class FakeBookkeeperAlgebraTest {
    private val keyA = TestingKey("users", "a")
    private val keyB = TestingKey("users", "b")
    private val other = TestingKey("teams", "a")
    private val meta = TestStoreMeta(writtenAtEpochMillis = 1L)

    @Test
    fun failureOnlyRecord_isNotDurablyStale_andCountsConsecutiveFailures() = runTest {
        val bk = FakeBookkeeper()
        bk.recordFailure(keyA, atEpochMillis = 10L)
        bk.recordFailure(keyA, atEpochMillis = 20L)
        val status = assertNotNull(bk.status(keyA))
        assertFalse(status.durablyStale) // no success + no covering mark -> false (?: 0 zero floor)
        assertEquals(2, status.consecutiveFailures)
        assertEquals(20L, status.lastFailureAtEpochMillis)
        assertNull(status.lastSuccessSequence)
        bk.recordSuccess(keyA, meta)
        val cleared = assertNotNull(bk.status(keyA))
        assertEquals(0, cleared.consecutiveFailures)      // success resets the streak
        assertNull(cleared.lastFailureAtEpochMillis)      // AND the timestamp (kit pin)
    }

    @Test
    fun perKeyMark_thenSuccess_clearsDurableStaleness() = runTest {
        val bk = FakeBookkeeper()
        bk.recordSuccess(keyA, meta)
        bk.markStale(keyA)
        assertTrue(assertNotNull(bk.status(keyA)).durablyStale)
        bk.recordSuccess(keyA, meta) // 304-style confirmation assigns a post-mark sequence
        assertFalse(assertNotNull(bk.status(keyA)).durablyStale)
    }

    @Test
    fun namespaceWatermark_coversItsNamespaceOnly_includingRecordlessKeys() = runTest {
        val bk = FakeBookkeeper()
        bk.recordSuccess(keyA, meta)
        bk.recordSuccess(other, meta)
        bk.advanceStaleWatermark(StoreNamespace("users"))
        assertTrue(assertNotNull(bk.status(keyA)).durablyStale)
        assertFalse(assertNotNull(bk.status(other)).durablyStale) // other namespace untouched
        assertTrue(assertNotNull(bk.status(keyB)).durablyStale)   // record-less key: status non-null via covering watermark
        bk.recordSuccess(keyA, meta)                               // post-watermark success clears coverage
        assertFalse(assertNotNull(bk.status(keyA)).durablyStale)
    }

    @Test
    fun globalWatermark_coversEverything() = runTest {
        val bk = FakeBookkeeper()
        bk.recordSuccess(other, meta)
        bk.advanceGlobalStaleWatermark()
        assertTrue(assertNotNull(bk.status(other)).durablyStale)
        assertTrue(assertNotNull(bk.status(keyA)).durablyStale) // no record needed
    }

    @Test
    fun forgetVariants_dropRecords_butWatermarksNeverReset() = runTest {
        val bk = FakeBookkeeper()
        bk.recordSuccess(keyA, meta)
        bk.recordSuccess(other, meta)
        bk.advanceStaleWatermark(StoreNamespace("users"))
        bk.forgetNamespace(StoreNamespace("users"))
        val statusA = assertNotNull(bk.status(keyA)) // record gone; watermark coverage persists
        assertNull(statusA.lastSuccessSequence)
        assertTrue(statusA.durablyStale)
        assertFalse(assertNotNull(bk.status(other)).durablyStale) // other namespace record intact
        bk.forget(other)
        bk.advanceGlobalStaleWatermark()
        bk.forgetAll()
        assertTrue(assertNotNull(bk.status(other)).durablyStale) // global watermark survives forgetAll
    }
}
