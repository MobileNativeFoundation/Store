package com.dropbox.android.external.cache4

import com.dropbox.android.external.cache4.CacheBuilderImpl.Companion.DEFAULT_CONCURRENCY_LEVEL
import com.dropbox.android.external.cache4.CacheBuilderImpl.Companion.UNSET_LONG
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.hours
import kotlin.time.nanoseconds

class CacheBuilderTest {

    @Test
    fun `set expireAfterWrite with zero duration`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Cache.Builder.newBuilder()
                .expireAfterWrite(0.nanoseconds)
                .build<Any, Any>() as RealCache
        }

        assertThat(exception).hasMessageThat().isEqualTo(
            "expireAfterWrite duration must be positive"
        )
    }

    @Test
    fun `set expireAfterWrite with positive duration`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterWrite(24.hours)
            .build<Any, Any>() as RealCache

        assertThat(cache.expireAfterWriteDuration)
            .isEqualTo(24.hours)
    }

    @Test
    fun `set expireAfterWrite with negative duration`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Cache.Builder.newBuilder()
                .expireAfterWrite((-1).nanoseconds)
                .build<Any, Any>() as RealCache
        }

        assertThat(exception).hasMessageThat().isEqualTo(
            "expireAfterWrite duration must be positive"
        )
    }

    @Test
    fun `set expireAfterAccess with zero duration`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Cache.Builder.newBuilder()
                .expireAfterAccess(0.nanoseconds)
                .build<Any, Any>() as RealCache
        }

        assertThat(exception).hasMessageThat().isEqualTo(
            "expireAfterAccess duration must be positive"
        )
    }

    @Test
    fun `set expireAfterAccess with positive duration`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterAccess(24.hours)
            .build<Any, Any>() as RealCache

        assertThat(cache.expireAfterAccessDuration)
            .isEqualTo(24.hours)
    }

    @Test
    fun `set expireAfterAccess with negative duration`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            Cache.Builder.newBuilder()
                .expireAfterAccess((-1).nanoseconds)
                .build<Any, Any>() as RealCache
        }

        assertThat(exception).hasMessageThat().isEqualTo(
            "expireAfterAccess duration must be positive"
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
