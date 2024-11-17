package org.mobilenativefoundation.store.store5

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.impl.extensions.get
import org.mobilenativefoundation.store.store5.test_utils.InMemoryPersister
import org.mobilenativefoundation.store.store5.test_utils.asSourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

class LocalOnlyTests {
    private val testScope = TestScope()

    @Test
    fun givenEmptyMemoryCacheThenCacheOnlyRequestReturnsNoNewData() =
        testScope.runTest {
            val store =
                StoreBuilder
                    .from(Fetcher.of { _: Int -> throw RuntimeException("Fetcher shouldn't be hit") })
                    .cachePolicy(
                        MemoryPolicy
                            .builder<Any, Any>()
                            .build(),
                    )
                    .build()
            val response = store.stream(StoreReadRequest.localOnly(0)).first()
            assertEquals(StoreReadResponse.NoNewData(StoreReadResponseOrigin.Cache), response)
        }

    @Test
    fun givenPrimedMemoryCacheThenCacheOnlyRequestReturnsData() =
        testScope.runTest {
            val fetcherHitCounter = atomic(0)
            val store =
                StoreBuilder
                    .from(
                        Fetcher.of { _: Int ->
                            fetcherHitCounter += 1
                            "result"
                        },
                    )
                    .cachePolicy(
                        MemoryPolicy
                            .builder<Any, Any>()
                            .build(),
                    )
                    .build()
            val a = store.get(0)
            assertEquals("result", a)
            assertEquals(1, fetcherHitCounter.value)
            val response = store.stream(StoreReadRequest.localOnly(0)).first()
            assertEquals("result", response.requireData())
            assertEquals(1, fetcherHitCounter.value)
        }

    @Test
    fun givenInvalidMemoryCacheThenCacheOnlyRequestReturnsNoNewData() =
        testScope.runTest {
            val fetcherHitCounter = atomic(0)
            val store =
                StoreBuilder
                    .from(
                        Fetcher.of { _: Int ->
                            fetcherHitCounter += 1
                            "result"
                        },
                    )
                    .cachePolicy(
                        MemoryPolicy
                            .builder<Any, Any>()
                            .setExpireAfterWrite(Duration.ZERO)
                            .build(),
                    )
                    .build()
            val a = store.get(0)
            assertEquals("result", a)
            assertEquals(1, fetcherHitCounter.value)
            val response = store.stream(StoreReadRequest.localOnly(0)).first()
            assertEquals(StoreReadResponse.NoNewData(StoreReadResponseOrigin.Cache), response)
            assertEquals(1, fetcherHitCounter.value)
        }

    @Test
    fun givenEmptyDiskCacheThenCacheOnlyRequestReturnsNoNewData() =
        testScope.runTest {
            val persister = InMemoryPersister<Int, String>()
            val store =
                StoreBuilder
                    .from(
                        fetcher = Fetcher.of { _: Int -> throw RuntimeException("Fetcher shouldn't be hit") },
                        sourceOfTruth = persister.asSourceOfTruth(),
                    )
                    .disableCache()
                    .build()
            val response = store.stream(StoreReadRequest.localOnly(0)).first()
            assertEquals(StoreReadResponse.NoNewData(StoreReadResponseOrigin.SourceOfTruth), response)
        }

    @Test
    fun givenPrimedDiskCacheThenCacheOnlyRequestReturnsData() =
        testScope.runTest {
            val fetcherHitCounter = atomic(0)
            val persister = InMemoryPersister<Int, String>()
            val store =
                StoreBuilder
                    .from(
                        fetcher =
                            Fetcher.of { _: Int ->
                                fetcherHitCounter += 1
                                "result"
                            },
                        sourceOfTruth = persister.asSourceOfTruth(),
                    )
                    .disableCache()
                    .build()
            val a = store.get(0)
            assertEquals("result", a)
            assertEquals(1, fetcherHitCounter.value)
            val response = store.stream(StoreReadRequest.localOnly(0)).first()
            assertEquals("result", response.requireData())
            assertEquals(StoreReadResponseOrigin.SourceOfTruth, response.origin)
            assertEquals(1, fetcherHitCounter.value)
        }

    @Test
    fun givenInvalidDiskCacheThenCacheOnlyRequestReturnsNoNewData() =
        testScope.runTest {
            val fetcherHitCounter = atomic(0)
            val persister = InMemoryPersister<Int, String>()
            persister.write(0, "result")
            val store =
                StoreBuilder
                    .from(
                        fetcher =
                            Fetcher.of { _: Int ->
                                fetcherHitCounter += 1
                                "result"
                            },
                        sourceOfTruth = persister.asSourceOfTruth(),
                    )
                    .disableCache()
                    .validator(Validator.by { false })
                    .build()
            val a = store.get(0)
            assertEquals("result", a)
            assertEquals(1, fetcherHitCounter.value)
            val response = store.stream(StoreReadRequest.localOnly(0)).first()
            assertEquals(StoreReadResponse.NoNewData(StoreReadResponseOrigin.SourceOfTruth), response)
            assertEquals(1, fetcherHitCounter.value)
        }

    @Test
    fun givenNoCacheThenCacheOnlyRequestReturnsNoNewData() =
        testScope.runTest {
            val store =
                StoreBuilder
                    .from(Fetcher.of { _: Int -> throw RuntimeException("Fetcher shouldn't be hit") })
                    .disableCache()
                    .build()
            val response = store.stream(StoreReadRequest.localOnly(0)).first()
            assertTrue(response is StoreReadResponse.NoNewData)
            assertEquals(StoreReadResponseOrigin.Cache, response.origin)
        }
}
