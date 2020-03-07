package com.dropbox.android.external.cache4

import com.dropbox.android.external.cache4.CacheBuilderImpl.Companion.DEFAULT_CONCURRENCY_LEVEL
import com.dropbox.android.external.cache4.CacheBuilderImpl.Companion.UNSET_LONG
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.hours
import kotlin.time.nanoseconds

@ExperimentalTime
class CacheBuilderTest {

    @Test
    fun `set expireAfterWrite with zero duration`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterWrite(0.nanoseconds)
            .build<Any, Any>() as RealCache

        assertThat(cache.expireAfterWriteDuration)
            .isEqualTo(Duration.ZERO)
    }

    @Test
    fun `set expireAfterWrite with positive duration`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterWrite(24.hours)
            .build<Any, Any>() as RealCache

        val expectedExpireAfterWriteDurationNs = 24 * 60 * 60 * 1000L * 1000L * 1000L

        assertThat(cache.expireAfterWriteDuration.toLongNanoseconds())
            .isEqualTo(expectedExpireAfterWriteDurationNs)
    }

    @Test
    fun `set expireAfterWrite with negative duration`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Cache.Builder.newBuilder()
                .expireAfterWrite((-1).nanoseconds)
                .build<Any, Any>() as RealCache
        }

        assertThat(exception).hasMessageThat().isEqualTo(
            "expireAfterWrite duration cannot be negative: -1.00ns"
        )
    }

    @Test
    fun `set expireAfterAccess with zero duration`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterAccess(0.nanoseconds)
            .build<Any, Any>() as RealCache

        assertThat(cache.expireAfterAccessDuration)
            .isEqualTo(Duration.ZERO)
    }

    @Test
    fun `set expireAfterAccess with positive duration`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterAccess(24.hours)
            .build<Any, Any>() as RealCache

        val expectedExpireAfterAccessDuration = 24 * 60 * 60 * 1000L * 1000L * 1000L

        assertThat(cache.expireAfterAccessDuration.toLongNanoseconds())
            .isEqualTo(expectedExpireAfterAccessDuration)
    }

    @Test
    fun `set expireAfterAccess with negative duration`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Cache.Builder.newBuilder()
                .expireAfterAccess((-1).nanoseconds)
                .build<Any, Any>() as RealCache
        }

        assertThat(exception).hasMessageThat().isEqualTo(
            "expireAfterAccess duration cannot be negative: -1.00ns"
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

        assertThat(exception).hasMessageThat().isEqualTo(
            "maximum size must not be negative"
        )
    }

    @Test
    fun `maxSize is 0 when expireAfterWrite has been set to zero`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterWrite(0.nanoseconds)
            .maximumCacheSize(100)
            .build<Any, Any>() as RealCache

        // when expireAfterWrite is explicitly set to 0 by user, max size will also be set to 0
        assertThat(cache.maxSize)
            .isEqualTo(0)
    }

    @Test
    fun `maxSize is 0 when expireAfterAccess has been set to 0`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterAccess(0.nanoseconds)
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

        assertThat(exception).hasMessageThat().isEqualTo(
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

        assertThat(exception).hasMessageThat().isEqualTo(
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
            .expireAfterWrite(1.nanoseconds)
            .expireAfterAccess(0.nanoseconds)
            .build<Any, Any>() as RealCache

        val timeToIdleCache = Cache.Builder.newBuilder()
            .expireAfterWrite(0.nanoseconds)
            .expireAfterAccess(1.nanoseconds)
            .build<Any, Any>() as RealCache

        assertThat(timeToLiveCache.clock)
            .isEqualTo(SystemClock)

        assertThat(timeToIdleCache.clock)
            .isEqualTo(SystemClock)
    }

    @Test
    fun `build cache with defaults`() {
        val cache = Cache.Builder.newBuilder().build<Any, Any>() as RealCache

        assertThat(cache.expireAfterWriteDuration)
            .isEqualTo(Duration.INFINITE)

        assertThat(cache.expireAfterAccessDuration)
            .isEqualTo(Duration.INFINITE)

        assertThat(cache.maxSize)
            .isEqualTo(UNSET_LONG)

        assertThat(cache.concurrencyLevel)
            .isEqualTo(DEFAULT_CONCURRENCY_LEVEL)

        assertThat(cache.clock)
            .isEqualTo(SystemClock)
    }
}
