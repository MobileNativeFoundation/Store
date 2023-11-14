package org.mobilenativefoundation.store.store5

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.impl.extensions.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

@FlowPreview
@ExperimentalCoroutinesApi
class StoreWithInMemoryCacheTests {
    private val testScope = TestScope()

    @Test
    fun storeRequestsCanCompleteWhenInMemoryCacheWithAccessExpiryIsAtTheMaximumSize() = testScope.runTest {
        val store = StoreBuilder
            .from(Fetcher.of { _: Int -> "result" })
            .cachePolicy(
                MemoryPolicy
                    .builder<Any, Any>()
                    .setExpireAfterAccess(1.hours)
                    .setMaxSize(1)
                    .build()
            )
            .build()

        val a = store.get(0)
        val b = store.get(0)
        val c = store.get(1)
        val d = store.get(2)

        assertEquals("result", a)
        assertEquals("result", b)
        assertEquals("result", c)
        assertEquals("result", d)
    }

    @Test
    fun givenEmptyCacheThenCacheOnlyRequestReturnsNoNewData() = testScope.runTest {
        val store = StoreBuilder
            .from(Fetcher.of { _: Int -> throw RuntimeException("Fetcher shouldn't be hit") })
            .cachePolicy(
                MemoryPolicy
                    .builder<Any, Any>()
                    .build()
            )
            .build()
        val response = store.stream(StoreReadRequest.cacheOnly(0)).first()
        assertEquals(StoreReadResponse.NoNewData(StoreReadResponseOrigin.Cache), response)
    }

    @Test
    fun givenPrimedCacheThenCacheOnlyRequestReturnsData() = testScope.runTest {
        val fetcherHitCounter = atomic(0)
        val store = StoreBuilder
            .from(Fetcher.of { _: Int ->
                fetcherHitCounter += 1
                "result"
            })
            .cachePolicy(
                MemoryPolicy
                    .builder<Any, Any>()
                    .build()
            )
            .build()
        val a = store.get(0)
        assertEquals("result", a)
        assertEquals(1, fetcherHitCounter.value)
        val response = store.stream(StoreReadRequest.cacheOnly(0)).first()
        assertEquals("result", response.requireData())
        assertEquals(1, fetcherHitCounter.value)
    }

    @Test
    fun givenInvalidCacheThenCacheOnlyRequestReturnsNoNewData() = testScope.runTest {
        val fetcherHitCounter = atomic(0)
        val store = StoreBuilder
            .from(Fetcher.of { _: Int ->
                fetcherHitCounter += 1
                "result"
            })
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
        val response = store.stream(StoreReadRequest.cacheOnly(0)).first()
        assertEquals(StoreReadResponse.NoNewData(StoreReadResponseOrigin.Cache), response)
        assertEquals(1, fetcherHitCounter.value)
    }
}
