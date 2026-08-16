package org.mobilenativefoundation.store6.testing

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.store
import kotlin.test.*

@OptIn(ExperimentalStoreApi::class)
class FakeBookkeeperRestartTest {
    @Test
    fun durableStaleness_survivesStoreRestart_throughSharedFakes() = runTest {
        val key = TestingKey("users", "1")
        val sot = FakeSourceOfTruth<TestingKey, String>()
        val bookkeeper = FakeBookkeeper()
        val fetcher = FakeFetcher<TestingKey, String>()
        fetcher.enqueue(key, FetcherResult.Success("v1"))

        val storeA = store<TestingKey, String> { fetcher(fetcher); persistence(sot); bookkeeper(bookkeeper) }
        assertEquals("v1", storeA.get(key))
        storeA.invalidate(key) // durable mark lands in the shared FakeBookkeeper (markStale under write lock)
        storeA.close()

        assertTrue(bookkeeper.status(key)!!.durablyStale) // the durable fact survived close()

        fetcher.enqueue(key, FetcherResult.Success("v2"))
        val storeB = store<TestingKey, String> { fetcher(fetcher); persistence(sot); bookkeeper(bookkeeper) }
        assertEquals("v2", storeB.get(key, Freshness.MustBeFresh)) // restart refetches
        assertFalse(bookkeeper.status(key)!!.durablyStale)         // recordSuccess cleared the mark
        assertEquals(2, fetcher.invocations.size)
        storeB.close()
    }
}
