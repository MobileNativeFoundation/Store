package com.dropbox.android.external.cache4

import com.dropbox.android.external.cache4.CacheBuilderImpl.Companion.DEFAULT_CONCURRENCY_LEVEL
import com.dropbox.android.external.cache4.CacheBuilderImpl.Companion.DEFAULT_EXPIRATION_NANOS
import com.dropbox.android.external.cache4.CacheBuilderImpl.Companion.UNSET_LONG
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.TimeUnit

class CacheBuilderTest {

    @Test
    fun `set expireAfterWrite with zero duration`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterWrite(0, TimeUnit.NANOSECONDS)
            .build<Any, Any>() as RealCache

        assertThat(cache.expireAfterWriteNanos)
            .isEqualTo(0)
    }

    @Test
    fun `set expireAfterWrite with positive duration`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build<Any, Any>() as RealCache

        val expectedExpireAfterWriteNanos = 24 * 60 * 60 * 1000L * 1000L * 1000L

        assertThat(cache.expireAfterWriteNanos)
            .isEqualTo(expectedExpireAfterWriteNanos)
    }

    @Test
    fun `set expireAfterWrite with negative duration`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Cache.Builder.newBuilder()
                .expireAfterWrite(-1, TimeUnit.NANOSECONDS)
                .build<Any, Any>() as RealCache
        }

        assertThat(exception).hasMessageThat().contains(
            "expireAfterWrite duration cannot be negative: -1 ${TimeUnit.NANOSECONDS}"
        )
    }

    @Test
    fun `set expireAfterAccess with zero duration`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterAccess(0, TimeUnit.NANOSECONDS)
            .build<Any, Any>() as RealCache

        assertThat(cache.expireAfterAccessNanos)
            .isEqualTo(0)
    }

    @Test
    fun `set expireAfterAccess with positive duration`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterAccess(24, TimeUnit.HOURS)
            .build<Any, Any>() as RealCache

        val expectedExpireAfterAccessNanos = 24 * 60 * 60 * 1000L * 1000L * 1000L

        assertThat(cache.expireAfterAccessNanos)
            .isEqualTo(expectedExpireAfterAccessNanos)
    }

    @Test
    fun `set expireAfterAccess with negative duration`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Cache.Builder.newBuilder()
                .expireAfterAccess(-1, TimeUnit.NANOSECONDS)
                .build<Any, Any>() as RealCache
        }

        assertThat(exception).hasMessageThat().contains(
            "expireAfterAccess duration cannot be negative: -1 ${TimeUnit.NANOSECONDS}"
        )
    }

    @Test
    fun `set maximumCacheSize to 0`() {
        val cache = Cache.Builder.newBuilder()
            .maximumCacheSize(0)
            .build<Any, Any>() as RealCache

        assertThat(cache.maxSize)
            .isEqualTo(0)
    }

    @Test
    fun `set maximumCacheSize to positive value`() {
        val cache = Cache.Builder.newBuilder()
            .maximumCacheSize(10)
            .build<Any, Any>() as RealCache

        assertThat(cache.maxSize)
            .isEqualTo(10)
    }

    @Test
    fun `set maximumCacheSize to negative value`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Cache.Builder.newBuilder()
                .maximumCacheSize(-1)
                .build<Any, Any>() as RealCache
        }

        assertThat(exception).hasMessageThat().contains(
            "maximum size must not be negative"
        )
    }

    @Test
    fun `maxSize is 0 when expireAfterWrite has been set to zero`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterWrite(0, TimeUnit.NANOSECONDS)
            .maximumCacheSize(100)
            .build<Any, Any>() as RealCache

        // when expireAfterWrite is explicitly set to 0 by user, max size will also be set to 0
        assertThat(cache.maxSize)
            .isEqualTo(0)
    }

    @Test
    fun `maxSize is 0 when expireAfterAccess has been set to 0`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterAccess(0, TimeUnit.NANOSECONDS)
            .maximumCacheSize(100)
            .build<Any, Any>() as RealCache

        // when expireAfterAccess is explicitly set to 0 by user, max size will also be set to 0
        assertThat(cache.maxSize).isEqualTo(0)
    }

    @Test
    fun `set concurrencyLevel to positive value`() {
        val cache = Cache.Builder.newBuilder()
            .concurrencyLevel(8)
            .build<Any, Any>() as RealCache

        assertThat(cache.concurrencyLevel)
            .isEqualTo(8)
    }

    @Test
    fun `set concurrencyLevel to 0`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Cache.Builder.newBuilder()
                .concurrencyLevel(0)
                .build<Any, Any>() as RealCache
        }

        assertThat(exception).hasMessageThat().contains(
            "concurrency level must be positive"
        )
    }

    @Test
    fun `set concurrencyLevel to negative value`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Cache.Builder.newBuilder()
                .concurrencyLevel(-1)
                .build<Any, Any>() as RealCache
        }

        assertThat(exception).hasMessageThat().contains(
            "concurrency level must be positive"
        )
    }

    @Test
    fun `set clock`() {
        val testClock = TestClock()
        val cache = Cache.Builder.newBuilder()
            .clock(testClock)
            .build<Any, Any>() as RealCache

        assertThat(cache.clock)
            .isEqualTo(testClock)
    }

    @Test
    fun `SystemClock is used when clock was not set explicitly and either expireAfterWrite or expireAfterAccess is positive`() {
        val timeToLiveCache = Cache.Builder.newBuilder()
            .expireAfterWrite(1, TimeUnit.NANOSECONDS)
            .expireAfterAccess(0, TimeUnit.NANOSECONDS)
            .build<Any, Any>() as RealCache

        val timeToIdleCache = Cache.Builder.newBuilder()
            .expireAfterWrite(0, TimeUnit.NANOSECONDS)
            .expireAfterAccess(1, TimeUnit.NANOSECONDS)
            .build<Any, Any>() as RealCache

        assertThat(timeToLiveCache.clock)
            .isEqualTo(SystemClock)

        assertThat(timeToIdleCache.clock)
            .isEqualTo(SystemClock)
    }

    @Test
    fun `build cache with defaults`() {
        val cache = Cache.Builder.newBuilder().build<Any, Any>() as RealCache

        assertThat(cache.expireAfterWriteNanos)
            .isEqualTo(DEFAULT_EXPIRATION_NANOS)

        assertThat(cache.expireAfterAccessNanos)
            .isEqualTo(DEFAULT_EXPIRATION_NANOS)

        assertThat(cache.maxSize)
            .isEqualTo(UNSET_LONG)

        assertThat(cache.concurrencyLevel)
            .isEqualTo(DEFAULT_CONCURRENCY_LEVEL)

        assertThat(cache.clock)
            .isEqualTo(SystemClock)
    }
}
