package org.mobilenativefoundation.store6.testing

import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.*
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.*

@OptIn(ExperimentalStoreApi::class)
class FakeFetcherIntegrationTest {
    @Test
    fun fakeFetcher_drivesRealStore_andRecordsInvocations() = runTest {
        val key = TestingKey("test", "1")
        val fetcher = FakeFetcher<TestingKey, String>()
        fetcher.enqueue(key, FetcherResult.Success("v1", etag = "e1"))
        val store = store<TestingKey, String> { fetcher(fetcher) }
        assertEquals("v1", store.get(key))
        val invocation = fetcher.invocations.single()
        assertEquals("1", invocation.key.canonicalId())
        assertNull(invocation.etag) // first fetch is unconditional
        store.close()
    }

    @Test
    fun conditionalRevalidation_recordsTheEtag() = runTest {
        val key = TestingKey("test", "3")
        val fetcher = FakeFetcher<TestingKey, String>()
        fetcher.enqueue(key, FetcherResult.Success("v1", etag = "e1"))
        val store = store<TestingKey, String> { fetcher(fetcher) }
        assertEquals("v1", store.get(key))                        // unconditional commit stores etag e1
        fetcher.enqueue(key, FetcherResult.NotModified(etag = "e1"))
        assertEquals("v1", store.get(key, Freshness.MustBeFresh)) // MustBeFresh + resident etag ->
                                                                  // FetchPlan.Conditional("e1"); 304 counts as fresh
        assertEquals(listOf(null, "e1"), fetcher.invocations.map { it.etag })
        store.close()
    }

    @Test
    fun unscriptedInvocation_surfacesAsTypedFetchError() = runTest {
        val fetcher = FakeFetcher<TestingKey, String>()
        val store = store<TestingKey, String> { fetcher(fetcher) }
        val ex = assertFailsWith<StoreException> { store.get(TestingKey("test", "2")) }
        assertIs<StoreError.Fetch>(ex.error)
        assertTrue(ex.cause?.message.orEmpty().contains("test/2")) // FakeFetcher names the key and fix
        store.close()
    }
}
