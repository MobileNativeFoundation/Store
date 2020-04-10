package com.dropbox.android.external.cache4

import com.dropbox.android.external.cache4.CacheBuilderImpl.Companion.DEFAULT_CONCURRENCY_LEVEL
import com.dropbox.android.external.cache4.CacheBuilderImpl.Companion.UNSET_LONG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.hours
import kotlin.time.nanoseconds

@ExperimentalTime
class CacheBuilderTest {

    @Test
    fun `set expireAfterWrite with zero duration`() {
        val exception = assertFailsWith(IllegalArgumentException::class) {
            Cache.Builder.newBuilder()
                .expireAfterWrite(0.nanoseconds)
                .build<Any, Any>() as RealCache
        }

        assertEquals("expireAfterWrite duration must be positive", exception.message)
    }

    @Test
    fun `set expireAfterWrite with positive duration`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterWrite(24.hours)
            .build<Any, Any>() as RealCache

        assertEquals(24.hours, cache.expireAfterWriteDuration)
    }

    @Test
    fun `set expireAfterWrite with negative duration`() {
        val exception = assertFailsWith(IllegalArgumentException::class) {
            Cache.Builder.newBuilder()
                .expireAfterWrite((-1).nanoseconds)
                .build<Any, Any>() as RealCache
        }

        assertEquals("expireAfterWrite duration must be positive", exception.message)
    }

    @Test
    fun `set expireAfterAccess with zero duration`() {
        val exception = assertFailsWith(IllegalArgumentException::class) {
            Cache.Builder.newBuilder()
                .expireAfterAccess(0.nanoseconds)
                .build<Any, Any>() as RealCache
        }

        assertEquals("expireAfterAccess duration must be positive", exception.message)
    }

    @Test
    fun `set expireAfterAccess with positive duration`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterAccess(24.hours)
            .build<Any, Any>() as RealCache

        assertEquals(24.hours, cache.expireAfterAccessDuration)
    }

    @Test
    fun `set expireAfterAccess with negative duration`() {
        val exception = assertFailsWith(IllegalArgumentException::class) {
            Cache.Builder.newBuilder()
                .expireAfterAccess((-1).nanoseconds)
                .build<Any, Any>() as RealCache
        }

        assertEquals("expireAfterAccess duration must be positive", exception.message)
    }

    @Test
    fun `set maximumCacheSize to 0`() {
        val cache = Cache.Builder.newBuilder()
            .maximumCacheSize(0)
            .build<Any, Any>() as RealCache

        assertEquals(0, cache.maxSize)
    }

    @Test
    fun `set maximumCacheSize to positive value`() {
        val cache = Cache.Builder.newBuilder()
            .maximumCacheSize(10)
            .build<Any, Any>() as RealCache

        assertEquals(10, cache.maxSize)
    }

    @Test
    fun `set maximumCacheSize to negative value`() {
        val exception = assertFailsWith(IllegalArgumentException::class) {
            Cache.Builder.newBuilder()
                .maximumCacheSize(-1)
                .build<Any, Any>() as RealCache
        }

        assertEquals("maximum size must not be negative", exception.message)
    }

    @Test
    fun `set concurrencyLevel to positive value`() {
        val cache = Cache.Builder.newBuilder()
            .concurrencyLevel(8)
            .build<Any, Any>() as RealCache

        assertEquals(8, cache.concurrencyLevel)
    }

    @Test
    fun `set concurrencyLevel to 0`() {
        val exception = assertFailsWith(IllegalArgumentException::class) {
            Cache.Builder.newBuilder()
                .concurrencyLevel(0)
                .build<Any, Any>() as RealCache
        }

        assertEquals("concurrency level must be positive", exception.message)
    }

    @Test
    fun `set concurrencyLevel to negative value`() {
        val exception = assertFailsWith(IllegalArgumentException::class) {
            Cache.Builder.newBuilder()
                .concurrencyLevel(-1)
                .build<Any, Any>() as RealCache
        }

        assertEquals("concurrency level must be positive", exception.message)
    }

    @Test
    fun `set clock`() {
        val testClock = TestClock()
        val cache = Cache.Builder.newBuilder()
            .clock(testClock)
            .build<Any, Any>() as RealCache

        assertEquals(testClock, cache.clock)
    }

    @Test
    fun `build cache with defaults`() {
        val cache = Cache.Builder.newBuilder().build<Any, Any>() as RealCache

        assertEquals(Duration.INFINITE, cache.expireAfterWriteDuration)

        assertEquals(Duration.INFINITE, cache.expireAfterAccessDuration)

        assertEquals(UNSET_LONG, cache.maxSize)

        assertEquals(DEFAULT_CONCURRENCY_LEVEL, cache.concurrencyLevel)

        assertEquals(SystemClock, cache.clock)
    }
}
