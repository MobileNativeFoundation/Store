package com.dropbox.android.external.cache4

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.TimeUnit

class CacheExpirationTest {

    private val clock = TestClock(virtualTimeNanos = 0)
    private val oneMinute = TimeUnit.MINUTES.toNanos(1)
    private val twoMinutes = TimeUnit.MINUTES.toNanos(2)

    @Test
    fun `cache never expires by default`() {
        val cache = CacheBuilder()
            .clock(clock)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        clock.virtualTimeNanos = Long.MAX_VALUE

        assertThat(cache.get(1))
            .isEqualTo("dog")

        assertThat(cache.get(2))
            .isEqualTo("cat")
    }

    @Test
    fun `cache entry gets evicted when expired after write`() {
        val cache = CacheBuilder()
            .clock(clock)
            .expireAfterWrite(oneMinute, TimeUnit.NANOSECONDS)
            .build<Long, String>()

        cache.put(1, "dog")

        // just before expiry
        clock.virtualTimeNanos = oneMinute - 1

        assertThat(cache.get(1))
            .isEqualTo("dog")

        // now expires
        clock.virtualTimeNanos = oneMinute

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `replacing a cache value resets the write expiry time`() {
        val cache = CacheBuilder()
            .clock(clock)
            .expireAfterWrite(oneMinute, TimeUnit.NANOSECONDS)
            .build<Long, String>()

        cache.put(1, "dog")

        // just before expiry
        clock.virtualTimeNanos = oneMinute - 1

        // update cache
        cache.put(1, "cat")

        // should not expire yet as cache was just updated
        clock.virtualTimeNanos = oneMinute

        assertThat(cache.get(1))
            .isEqualTo("cat")

        // should now expire
        clock.virtualTimeNanos = oneMinute * 2 - 1

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `reading a cache entry does not reset the write expiry time`() {
        val cache = CacheBuilder()
            .clock(clock)
            .expireAfterWrite(oneMinute, TimeUnit.NANOSECONDS)
            .build<Long, String>()

        cache.put(1, "dog")

        // just before expiry
        clock.virtualTimeNanos = oneMinute - 1

        // read cache before expected write expiry
        assertThat(cache.get(1))
            .isEqualTo("dog")

        // should expire despite cache just being read
        clock.virtualTimeNanos = oneMinute

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `cache entry gets evicted when expired after access`() {
        val cache = CacheBuilder()
            .clock(clock)
            .expireAfterAccess(twoMinutes, TimeUnit.NANOSECONDS)
            .build<Long, String>()

        cache.put(1, "dog")

        // read cache immediately
        assertThat(cache.get(1))
            .isEqualTo("dog")

        // now expires
        clock.virtualTimeNanos = twoMinutes

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `replacing a cache value resets the access expiry time`() {
        val cache = CacheBuilder()
            .clock(clock)
            .expireAfterAccess(twoMinutes, TimeUnit.NANOSECONDS)
            .build<Long, String>()

        cache.put(1, "dog")

        // just before expiry
        clock.virtualTimeNanos = twoMinutes - 1

        // update cache
        cache.put(1, "cat")

        // should not expire yet as cache was just updated (accessed)
        clock.virtualTimeNanos = twoMinutes

        assertThat(cache.get(1))
            .isEqualTo("cat")

        // should now expire
        clock.virtualTimeNanos = twoMinutes * 2

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `reading a cache entry resets the access expiry time`() {
        val cache = CacheBuilder()
            .clock(clock)
            .expireAfterAccess(twoMinutes, TimeUnit.NANOSECONDS)
            .build<Long, String>()

        cache.put(1, "dog")

        // just before expiry
        clock.virtualTimeNanos = twoMinutes - 1

        // read cache before expected access expiry
        assertThat(cache.get(1))
            .isEqualTo("dog")

        // should not expire yet as cache was just read (accessed)
        clock.virtualTimeNanos = twoMinutes

        assertThat(cache.get(1))
            .isEqualTo("dog")

        // should now expire
        clock.virtualTimeNanos = twoMinutes * 2

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `cache expiration respects both expireAfterWrite and expireAfterAccess`() {
        val cache = CacheBuilder()
            .clock(clock)
            .expireAfterWrite(twoMinutes, TimeUnit.NANOSECONDS)
            .expireAfterAccess(oneMinute, TimeUnit.NANOSECONDS)
            .build<Long, String>()

        cache.put(1, "dog")

        // expires due to access expiry
        clock.virtualTimeNanos = oneMinute

        assertThat(cache.get(1))
            .isNull()

        // cache a new value
        cache.put(1, "cat")

        // before new access expiry
        clock.virtualTimeNanos = twoMinutes - 1

        // this should resets access expiry time but not write expiry time
        assertThat(cache.get(1))
            .isEqualTo("cat")

        // should now expire due to write expiry
        clock.virtualTimeNanos = oneMinute + twoMinutes - 1

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `only expired cache entries are evicted`() {
        val cache = CacheBuilder()
            .clock(clock)
            .expireAfterWrite(oneMinute, TimeUnit.NANOSECONDS)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        // cache a new value
        clock.virtualTimeNanos = oneMinute / 2
        cache.put(3, "bird")

        // now first 2 entries should expire, 3rd entry should not expire yet
        clock.virtualTimeNanos = oneMinute

        assertThat(cache.get(1))
            .isNull()

        assertThat(cache.get(2))
            .isNull()

        assertThat(cache.get(3))
            .isEqualTo("bird")

        // just before 3rd entry expires
        clock.virtualTimeNanos = oneMinute + oneMinute / 2 - 1

        assertThat(cache.get(3))
            .isEqualTo("bird")

        // 3rd entry should now expire
        clock.virtualTimeNanos = oneMinute + oneMinute / 2

        assertThat(cache.get(3))
            .isNull()
    }

    @Test
    fun `cache entry gets evicted when exceeding maximum size before expected expiry`() {
        val cache = CacheBuilder()
            .clock(clock)
            .maximumCacheSize(2)
            .expireAfterWrite(oneMinute, TimeUnit.NANOSECONDS)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        // add a new cache entry before first entry is expected to expire
        clock.virtualTimeNanos = oneMinute / 2
        cache.put(3, "bird")

        // first entry should be evicted despite not being expired

        assertThat(cache.get(1))
            .isNull()

        assertThat(cache.get(2))
            .isEqualTo("cat")

        assertThat(cache.get(3))
            .isEqualTo("bird")
    }

    @Test
    fun `no values are cached when expireAfterWrite is explicitly set to 0`() {
        val cache = CacheBuilder()
            .clock(clock)
            .expireAfterWrite(0, TimeUnit.NANOSECONDS)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        assertThat(cache.get(1))
            .isNull()

        assertThat(cache.get(2))
            .isNull()
    }

    @Test
    fun `no values are cached when expireAfterAccess is explicitly set to 0`() {
        val cache = CacheBuilder()
            .clock(clock)
            .expireAfterAccess(0, TimeUnit.NANOSECONDS)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        assertThat(cache.get(1))
            .isNull()

        assertThat(cache.get(2))
            .isNull()
    }
}
