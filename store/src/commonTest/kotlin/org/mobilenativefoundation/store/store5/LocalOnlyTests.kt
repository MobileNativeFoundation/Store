package org.mobilenativefoundation.store.store5

import app.cash.turbine.test
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.impl.extensions.get
import org.mobilenativefoundation.store.store5.util.InMemoryPersister
import org.mobilenativefoundation.store.store5.util.asSourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import org.mobilenativefoundation.store.store5.impl.extensions.fresh

class LocalOnlyTests {
    private val testScope = TestScope()

    @Test
    fun givenEmptyMemoryCacheThenCacheOnlyRequestReturnsNoNewData() = testScope.runTest {
        val store = StoreBuilder
            .from(Fetcher.of { _: Int -> throw RuntimeException("Fetcher shouldn't be hit") })
            .cachePolicy(
                MemoryPolicy
                    .builder<Any, Any>()
                    .build()
            )
            .build()
        store.stream(StoreReadRequest.localOnly(0)).test {
            assertEquals(StoreReadResponse.NoNewData(StoreReadResponseOrigin.Cache), awaitItem())
        }
    }

    @Test
    fun givenPrimedMemoryCacheThenCacheOnlyRequestReturnsData() = testScope.runTest {
        val fetcherHitCounter = atomic(0)
        val store = StoreBuilder
            .from(
                Fetcher.of { _: Int ->
                    fetcherHitCounter += 1
                    "result"
                }
            )
            .cachePolicy(
                MemoryPolicy
                    .builder<Any, Any>()
                    .build()
            )
            .build()
        val a = store.get(0)
        assertEquals("result", a)
        assertEquals(1, fetcherHitCounter.value)
        store.stream(StoreReadRequest.localOnly(0)).test {
            assertEquals("result", awaitItem().requireData())
            assertEquals(1, fetcherHitCounter.value)
        }
    }

    @Test
    fun givenInvalidMemoryCacheThenCacheOnlyRequestReturnsNoNewData() = testScope.runTest {
        val fetcherHitCounter = atomic(0)
        val store = StoreBuilder
            .from(
                Fetcher.of { _: Int ->
                    fetcherHitCounter += 1
                    "result"
                }
            )
            .cachePolicy(
                MemoryPolicy
                    .builder<Any, Any>()
                    .setExpireAfterWrite(Duration.ZERO)
                    .build()
            )
            .build()
        val a = store.get(0)
        assertEquals("result", a)
        assertEquals(1, fetcherHitCounter.value)
        store.stream(StoreReadRequest.localOnly(0)).test {
            assertEquals(StoreReadResponse.NoNewData(StoreReadResponseOrigin.Cache), awaitItem())
            assertEquals(1, fetcherHitCounter.value)
        }

    }

    @Test
    fun givenEmptyDiskCacheThenCacheOnlyRequestReturnsNoNewData() = testScope.runTest {
        val persister = InMemoryPersister<Int, String>()
        val store = StoreBuilder
            .from(
                fetcher = Fetcher.of { _: Int -> throw RuntimeException("Fetcher shouldn't be hit") },
                sourceOfTruth = persister.asSourceOfTruth()
            )
            .disableCache()
            .build()
        store.stream(StoreReadRequest.localOnly(0)).test {
            assertEquals(StoreReadResponse.NoNewData(StoreReadResponseOrigin.SourceOfTruth), awaitItem())
        }
    }

    @Test
    fun givenPrimedDiskCacheThenCacheOnlyRequestReturnsData() = testScope.runTest {
        val fetcherHitCounter = atomic(0)
        val persister = InMemoryPersister<Int, String>()
        val store = StoreBuilder
            .from(
                fetcher = Fetcher.of { _: Int ->
                    fetcherHitCounter += 1
                    "result"
                },
                sourceOfTruth = persister.asSourceOfTruth()
            )
            .disableCache()
            .build()
        val a = store.get(0)
        assertEquals("result", a)
        assertEquals(1, fetcherHitCounter.value)
        store.stream(StoreReadRequest.localOnly(0)).test {
            val response = awaitItem()
            assertEquals("result", response.requireData())
            assertEquals(StoreReadResponseOrigin.SourceOfTruth, response.origin)
            assertEquals(1, fetcherHitCounter.value)
        }
    }

    @Test
    fun givenInvalidDiskCacheThenCacheOnlyRequestReturnsNoNewData() = testScope.runTest {
        val fetcherHitCounter = atomic(0)
        val persister = InMemoryPersister<Int, String>()
        persister.write(0, "result")
        val store = StoreBuilder
            .from(
                fetcher = Fetcher.of { _: Int ->
                    fetcherHitCounter += 1
                    "result"
                },
                sourceOfTruth = persister.asSourceOfTruth()
            )
            .disableCache()
            .validator(Validator.by { false })
            .build()
        val a = store.get(0)
        assertEquals("result", a)
        assertEquals(1, fetcherHitCounter.value)
        store.stream(StoreReadRequest.localOnly(0)).test {
            assertEquals(StoreReadResponse.NoNewData(StoreReadResponseOrigin.SourceOfTruth), awaitItem())
            assertEquals(1, fetcherHitCounter.value)
        }
    }

    @Test
    fun givenNoCacheThenCacheOnlyRequestReturnsNoNewData() = testScope.runTest {
        val store = StoreBuilder
            .from(Fetcher.of { _: Int -> throw RuntimeException("Fetcher shouldn't be hit") })
            .disableCache()
            .build()
        store.stream(StoreReadRequest.localOnly(0)).test {
            val response = awaitItem()
            assertTrue(response is StoreReadResponse.NoNewData)
            assertEquals(StoreReadResponseOrigin.Cache, response.origin)
        }
    }

    @Test
    fun collectNewDataFromFetcher() = testScope.runTest {
        val fetcherHitCounter = atomic(0)
        val store = StoreBuilder
            .from(
                Fetcher.of { _: Int ->
                    fetcherHitCounter += 1
                    "result $fetcherHitCounter"
                }
            )
            .cachePolicy(
                MemoryPolicy
                    .builder<Int, String>()
                    .build()
            )
            .build()

        store.stream(StoreReadRequest.localOnly(0)).test {
            assertTrue(awaitItem() is StoreReadResponse.NoNewData)

            assertEquals("result 1", store.fresh(0))
            assertEquals("result 1", awaitItem().requireData())

            assertEquals("result 2", store.fresh(0))
            assertEquals("result 2", awaitItem().requireData())

            // different key, not collected
            assertEquals("result 3", store.fresh(1))
        }
    }
}
