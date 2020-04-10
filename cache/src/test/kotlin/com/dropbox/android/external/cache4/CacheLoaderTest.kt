package com.dropbox.android.external.cache4

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.Test
import kotlin.time.ExperimentalTime
import kotlin.time.minutes
import kotlin.time.nanoseconds

@ExperimentalTime
class CacheLoaderTest {

    private val clock = TestClock(virtualDuration = 0.nanoseconds)
    private val expiryDuration = 1.minutes

    @Test
    fun `get(key, loader) returns value from loader when no value with the associated key exists before and after executing the loader`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        val loader = createLoader("dog")

        val value = cache.get(1, loader)

        assertEquals(1, loader.invokeCount)

        assertEquals("dog", value)
    }

    @Test
    fun `get(key, loader) returns value from loader when an expired value with the associated key exists before executing the loader and none exists after executing the loader`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterWrite(expiryDuration)
            .clock(clock)
            .build<Long, String>()

        cache.put(1, "cat")

        // now expires
        clock.virtualDuration = expiryDuration

        val loader = createLoader("dog")

        val value = cache.get(1, loader)

        assertEquals(1, loader.invokeCount)

        assertEquals("dog", value)
    }

    @Test
    fun `get(key, loader) returns existing value when an unexpired entry with the associated key exists before executing the loader`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterAccess(expiryDuration)
            .build<Long, String>()

        cache.put(1, "dog")

        // just before expiry
        clock.virtualDuration = expiryDuration - 1.nanoseconds

        val loader = createLoader("cat")

        val value = cache.get(1, loader)

        assertEquals(0, loader.invokeCount)

        assertEquals("dog", value)
    }

    @Test
    fun `get(key, loader) returns existing value when an entry with the associated key is absent initially but present after executing the loader`() =
        runBlocking {
            val cache = Cache.Builder.newBuilder()
                .concurrencyLevel(2)
                .build<Long, String>()

            val executionTime = 50L

            val loader = createSlowLoader("dog", executionTime)

            var value: String? = null

            val loaderJob = launch(newSingleThreadDispatcher()) {
                value = cache.get(1, loader)
            }

            val putJob = launch(newSingleThreadDispatcher()) {
                delay(10)
                cache.put(1, "cat")
            }

            loaderJob.join()
            putJob.join()

            assertEquals(1, loader.invokeCount)

            // entry from loader should not be cached as an entry already exists
            // by the time loader returns
            assertEquals("cat", value)

            assertEquals("cat", cache.get(1))
        }

    @Test
    fun `value returned by loader is cached when value associated with the key is still absent after executing the loader`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        val loader = createLoader("dog")

        cache.get(1, loader)

        assertEquals(1, loader.invokeCount)

        assertEquals("dog", cache.get(1))
    }

    @Test
    fun `value returned by loader is cached when value associated with the key is present but expired after executing the loader`() =
        runBlocking {
            val cache = Cache.Builder.newBuilder()
                .expireAfterWrite(expiryDuration)
                .concurrencyLevel(2)
                .clock(clock)
                .build<Long, String>()

            val executionTime = 50L

            val loader = createSlowLoader("dog", executionTime)

            var value: String? = null

            val loaderJob = launch(newSingleThreadDispatcher()) {
                value = cache.get(1, loader)
            }

            val putJob = launch(newSingleThreadDispatcher()) {
                delay(10)
                cache.put(1, "cat")

                // now expires
                clock.virtualDuration = expiryDuration
            }

            loaderJob.join()
            putJob.join()

            assertEquals(1, loader.invokeCount)

            // entry from loader should be cached as the existing one has expired.
            assertEquals("dog", value)

            assertEquals("dog", cache.get(1))
        }

    @Test
    fun `value returned by loader is not cached when an unexpired value associated with the key exists`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        cache.put(1, "dog")

        val loader = createLoader("cat")

        cache.get(1, loader)

        assertEquals(0, loader.invokeCount)

        assertEquals("dog", cache.get(1))
    }

    @Test
    fun `value returned by loader is cached when an existing value was invalidated while executing loader`() =
        runBlocking {
            val cache = Cache.Builder.newBuilder()
                .concurrencyLevel(2)
                .build<Long, String>()

            val executionTime = 50L

            val loader = createSlowLoader("dog", executionTime)

            var value: String? = null

            val loaderJob = launch(newSingleThreadDispatcher()) {
                value = cache.get(1, loader)
            }

            val putJob = launch(newSingleThreadDispatcher()) {
                delay(10)
                cache.put(1, "cat")

                // invalidate the entry
                cache.invalidate(1)
            }

            loaderJob.join()
            putJob.join()

            assertEquals(1, loader.invokeCount)

            // entry from loader should be cached as previous one had been invalidated.
            assertEquals("dog", value)

            assertEquals("dog", cache.get(1))
        }

    @Test
    fun `only 1 loader is executed for multiple concurrent get(key, loader) calls with the same key`() =
        runBlocking {
            val cache = Cache.Builder.newBuilder()
                .concurrencyLevel(4)
                .build<Long, String>()

            val executionTime = 20L

            val loader = createSlowLoader("cat", executionTime)

            (0 until 3).map {
                launch(newSingleThreadDispatcher()) {
                    // all calls use the same key
                    cache.get(1, loader)
                }
            }.joinAll()

            assertEquals(1, loader.invokeCount)

            assertEquals("cat", cache.get(1))
        }

    @Test
    fun `each loader is executed for multiple concurrent get(key, loader) calls with different keys`() =
        runBlocking {
            val cache = Cache.Builder.newBuilder()
                .concurrencyLevel(4)
                .build<Long, String>()

            val executionTime = 20L

            val loader = createSlowLoader("cat", executionTime)

            (0 until 3).map {
                launch(newSingleThreadDispatcher()) {
                    // each call uses a different key
                    cache.get(it.toLong(), loader)
                }
            }.joinAll()

            assertEquals(3, loader.invokeCount)

            assertEquals("cat", cache.get(1))
        }

    @Test
    fun `a loader exception is propagated to the get(key, loader) call`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        val loader = createFailingLoader(TestLoaderException())

        assertFailsWith(TestLoaderException::class) {
            cache.get(1, loader)
        }

        // loader shouldn't complete as it throws an exception during invocation
        assertEquals(0, loader.invokeCount)

        assertNull(cache.get(1))
    }

    @Test
    fun `a blocked concurrent get(key, loader) call is unblocked and executes its own loader after the loader from an earlier concurrent call throws an exception`() =
        runBlocking {
            val cache = Cache.Builder.newBuilder()
                .concurrencyLevel(2)
                .build<Long, String>()

            val executionTime = 50L

            val loader1 = createSlowFailingLoader(TestLoaderException(), executionTime)
            val loader2 = createLoader("cat")

            var value: String? = null

            val loader1Job = launch(newSingleThreadDispatcher()) {
                runCatching {
                    cache.get(1, loader1)
                }
            }

            val loader2Job = launch(newSingleThreadDispatcher()) {
                delay(10)
                value = cache.get(1, loader2)
            }

            loader1Job.join()
            loader2Job.join()

            // loader1 shouldn't complete as it throws an exception during invocation
            assertEquals(0, loader1.invokeCount)
            assertEquals(1, loader2.invokeCount)

            assertEquals("cat", value)

            assertEquals("cat", cache.get(1))
        }
}

private class TestLoader<Value>(private val block: () -> Value) : () -> Value {
    private val _invokeCount = atomic(0)
    val invokeCount get() = _invokeCount.value
    override operator fun invoke(): Value {
        val result = block()
        _invokeCount.incrementAndGet()
        return result
    }
}

private fun <Value> createLoader(computedValue: Value) =
    TestLoader { computedValue }

private fun <Value> createSlowLoader(
    computedValue: Value,
    executionTime: Long
) = TestLoader {
    runBlocking { delay(executionTime) }
    computedValue
}

private fun createFailingLoader(exception: Exception) =
    TestLoader { throw exception }

@Suppress("SameParameterValue")
private fun createSlowFailingLoader(exception: Exception, executionTime: Long) =
    TestLoader {
        runBlocking { delay(executionTime) }
        throw exception
    }
