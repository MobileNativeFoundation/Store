package com.nytimes.suspendCache

import com.com.nytimes.suspendCache.RealStoreCache
import com.nytimes.android.external.cache3.Ticker
import com.nytimes.android.external.store3.base.impl.MemoryPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.runBlockingTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.*
import java.util.concurrent.TimeUnit

@Suppress("UsePropertyAccessSyntax") // for isTrue() / isFalse()
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class RealStoreCacheTest {
    private val testScope = TestCoroutineScope()
    private val loader = TestLoader()
    private val ticker = object : Ticker() {
        override fun read(): Long {
            return TimeUnit.MILLISECONDS.toNanos(testScope.currentTime)
        }
    }

    @Test
    fun sanity_notEnquedShouldThrow() = testScope.runBlockingTest {
        val cache = createCache()
        try {
            cache.get("unused key")
            fail("should've failed")
        } catch (assertionError: AssertionError) {
            assertThat(assertionError.localizedMessage).isEqualTo("nothing enqueued")
        }
    }

    @Test
    fun cache() = testScope.runBlockingTest {
        val cache = createCache()
        loader.enqueueResponse("foo", "bar")
        assertThat(cache.get("foo")).isEqualTo("bar")
        // get again, is cached
        assertThat(cache.get("foo")).isEqualTo("bar")
    }

    @Test
    fun cache_expired() = testScope.runBlockingTest {
        val cache = createCache(
                MemoryPolicy.builder()
                        .setExpireAfterAccess(10)
                        .setExpireAfterTimeUnit(TimeUnit.MILLISECONDS)
                        .build())
        loader.enqueueResponse("foo", "bar")
        loader.enqueueResponse("foo", "bar_updated")
        assertThat(cache.get("foo")).isEqualTo("bar")
        // get again, is cached
        assertThat(cache.get("foo")).isEqualTo("bar")
        testScope.advanceTimeBy(11)
        assertThat(cache.get("foo")).isEqualTo("bar_updated")
    }

    @Test
    fun getIfPresent() = testScope.runBlockingTest {
        val cache = createCache()
        assertThat(cache.getIfPresent("foo")).isNull()
        loader.enqueueResponse("foo", "bar")
        assertThat(cache.getIfPresent("foo")).isNull()
        assertThat(cache.get("foo")).isEqualTo("bar")
        assertThat(cache.getIfPresent("foo")).isEqualTo("bar")
    }

    @Test
    fun getIfPresent_pendingFetch() = testScope.runBlockingTest {
        val cache = createCache()
        val deferredResult = CompletableDeferred<String>()
        loader.enqueueResponse("foo", deferredResult)
        val asyncGet = async {
            cache.get("foo")
        }
        testScope.advanceUntilIdle()
        assertThat(asyncGet.isCompleted).isFalse()
        assertThat(cache.getIfPresent("foo")).isNull()
        deferredResult.complete("bar")
        testScope.advanceUntilIdle()
        assertThat(asyncGet.isCompleted).isTrue()
        assertThat(cache.getIfPresent("foo")).isEqualTo("bar")
    }

    @Test
    fun invalidate() = testScope.runBlockingTest {
        val cache = createCache()
        loader.enqueueResponse("foo", "bar")
        loader.enqueueResponse("foo", "bar_updated")
        assertThat(cache.get("foo")).isEqualTo("bar")
        cache.invalidate("foo")
        assertThat(cache.get("foo")).isEqualTo("bar_updated")
    }

    @Test
    fun clearAll() = testScope.runBlockingTest {
        val cache = createCache()
        loader.enqueueResponse("foo", "bar")
        loader.enqueueResponse("foo", "bar_updated")
        loader.enqueueResponse("baz", "bat")
        loader.enqueueResponse("baz", "bat_updated")

        assertThat(cache.get("foo")).isEqualTo("bar")
        assertThat(cache.get("baz")).isEqualTo("bat")
        cache.clearAll()
        assertThat(cache.get("foo")).isEqualTo("bar_updated")
        assertThat(cache.get("baz")).isEqualTo("bat_updated")
    }

    @Test
    fun put() = testScope.runBlockingTest {
        val cache = createCache()
        cache.put("foo", "bar")
        assertThat(cache.get("foo")).isEqualTo("bar")
        cache.put("foo", "bar_updated")
        assertThat(cache.get("foo")).isEqualTo("bar_updated")
    }

    @Test
    fun fresh() = testScope.runBlockingTest {
        val cache = createCache()
        cache.put("foo", "bar")
        loader.enqueueResponse("foo", "bar_updated")
        assertThat(cache.fresh("foo")).isEqualTo("bar_updated")
        assertThat(cache.get("foo")).isEqualTo("bar_updated")
    }

    private fun createCache(
            memoryPolicy: MemoryPolicy = MemoryPolicy.builder().build()
    ): RealStoreCache<String, String, String> {
        return RealStoreCache(
                loader = loader::invoke,
                ticker = ticker,
                memoryPolicy = memoryPolicy
        )
    }

    private suspend fun <K, V> RealStoreCache<K, V, K>.get(key : K) = get(key, key)
    private suspend fun <K, V> RealStoreCache<K, V, K>.fresh(key : K) = fresh(key, key)
}

private class TestLoader {
    private val enqueued = mutableMapOf<String, LinkedList<Deferred<String>>>()

    fun enqueueResponse(key: String, value: String) {
        enqueueResponse(key, CompletableDeferred(value))
    }

    fun enqueueResponse(key: String, deferred: Deferred<String>) {
        enqueued.getOrPut(key) {
            LinkedList()
        }.add(deferred)
    }

    suspend fun invoke(key: String): String {
        val response = enqueued[key]?.pop() ?: throw AssertionError("nothing enqueued")
        return response.await()
    }
}
