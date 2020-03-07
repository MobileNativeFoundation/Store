package com.dropbox.android.external.cache4

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.minutes
import kotlin.time.nanoseconds

@ExperimentalTime
class CacheExpirationTest {

    private val clock = TestClock(virtualDuration = 0.nanoseconds)

    @Test
    fun `cache never expires by default`() {
        val cache = Cache.Builder.newBuilder()
            .clock(clock)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        clock.virtualDuration = Duration.INFINITE

        assertThat(cache.get(1))
            .isEqualTo("dog")

        assertThat(cache.get(2))
            .isEqualTo("cat")
    }

    @Test
    fun `cache entry gets evicted when expired after write`() {
        val cache = Cache.Builder.newBuilder()
            .clock(clock)
            .expireAfterWrite(1.minutes)
            .build<Long, String>()

        cache.put(1, "dog")

        // just before expiry
        clock.virtualDuration = 1.minutes - 1.nanoseconds

        assertThat(cache.get(1))
            .isEqualTo("dog")

        // now expires
        clock.virtualDuration = 1.minutes

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `replacing a cache value resets the write expiry time`() {
        val cache = Cache.Builder.newBuilder()
            .clock(clock)
            .expireAfterWrite(1.minutes)
            .build<Long, String>()

        cache.put(1, "dog")

        // just before expiry
        clock.virtualDuration = 1.minutes - 1.nanoseconds

        // update cache
        cache.put(1, "cat")

        // should not expire yet as cache was just updated
        clock.virtualDuration = 1.minutes

        assertThat(cache.get(1))
            .isEqualTo("cat")

        // should now expire
        clock.virtualDuration = 1.minutes * 2 - 1.nanoseconds

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `reading a cache entry does not reset the write expiry time`() {
        val cache = Cache.Builder.newBuilder()
            .clock(clock)
            .expireAfterWrite(1.minutes)
            .build<Long, String>()

        cache.put(1, "dog")

        // just before expiry
        clock.virtualDuration = 1.minutes - 1.nanoseconds

        // read cache before expected write expiry
        assertThat(cache.get(1))
            .isEqualTo("dog")

        // should expire despite cache just being read
        clock.virtualDuration = 1.minutes

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `cache entry gets evicted when expired after access`() {
        val cache = Cache.Builder.newBuilder()
            .clock(clock)
            .expireAfterAccess(2.minutes)
            .build<Long, String>()

        cache.put(1, "dog")

        // read cache immediately
        assertThat(cache.get(1))
            .isEqualTo("dog")

        // now expires
        clock.virtualDuration = 2.minutes

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `replacing a cache value resets the access expiry time`() {
        val cache = Cache.Builder.newBuilder()
            .clock(clock)
            .expireAfterAccess(2.minutes)
            .build<Long, String>()

        cache.put(1, "dog")

        // just before expiry
        clock.virtualDuration = 2.minutes - 1.nanoseconds

        // update cache
        cache.put(1, "cat")

        // should not expire yet as cache was just updated (accessed)
        clock.virtualDuration = 2.minutes

        assertThat(cache.get(1))
            .isEqualTo("cat")

        // should now expire
        clock.virtualDuration = 2.minutes * 2

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `reading a cache entry resets the access expiry time`() {
        val cache = Cache.Builder.newBuilder()
            .clock(clock)
            .expireAfterAccess(2.minutes)
            .build<Long, String>()

        cache.put(1, "dog")

        // just before expiry
        clock.virtualDuration = 2.minutes - 1.nanoseconds

        // read cache before expected access expiry
        assertThat(cache.get(1))
            .isEqualTo("dog")

        // should not expire yet as cache was just read (accessed)
        clock.virtualDuration = 2.minutes

        assertThat(cache.get(1))
            .isEqualTo("dog")

        // should now expire
        clock.virtualDuration = 2.minutes * 2

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `cache expiration respects both expireAfterWrite and expireAfterAccess`() {
        val cache = Cache.Builder.newBuilder()
            .clock(clock)
            .expireAfterWrite(2.minutes)
            .expireAfterAccess(1.minutes)
            .build<Long, String>()

        cache.put(1, "dog")

        // expires due to access expiry
        clock.virtualDuration = 1.minutes

        assertThat(cache.get(1))
            .isNull()

        // cache a new value
        cache.put(1, "cat")

        // before new access expiry
        clock.virtualDuration = 2.minutes - 1.nanoseconds

        // this should resets access expiry time but not write expiry time
        assertThat(cache.get(1))
            .isEqualTo("cat")

        // should now expire due to write expiry
        clock.virtualDuration = (1.minutes + 2.minutes - 1.nanoseconds)

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `only expired cache entries are evicted`() {
        val cache = Cache.Builder.newBuilder()
            .clock(clock)
            .expireAfterWrite(1.minutes)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        // cache a new value
        clock.virtualDuration = 1.minutes / 2
        cache.put(3, "bird")

        // now first 2 entries should expire, 3rd entry should not expire yet
        clock.virtualDuration = 1.minutes

        assertThat(cache.get(1))
            .isNull()

        assertThat(cache.get(2))
            .isNull()

        assertThat(cache.get(3))
            .isEqualTo("bird")

        // just before 3rd entry expires
        clock.virtualDuration = 1.minutes + 1.minutes / 2 - 1.nanoseconds

        assertThat(cache.get(3))
            .isEqualTo("bird")

        // 3rd entry should now expire
        clock.virtualDuration = 1.minutes + 1.minutes / 2

        assertThat(cache.get(3))
            .isNull()
    }

    @Test
    fun `cache entry gets evicted when exceeding maximum size before expected expiry`() {
        val cache = Cache.Builder.newBuilder()
            .clock(clock)
            .maximumCacheSize(2)
            .expireAfterWrite(1.minutes)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        // add a new cache entry before first entry is expected to expire
        clock.virtualDuration = 1.minutes / 2
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
        val cache = Cache.Builder.newBuilder()
            .clock(clock)
            .expireAfterWrite(0.nanoseconds)
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
        val cache = Cache.Builder.newBuilder()
            .clock(clock)
            .expireAfterAccess(0.nanoseconds)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        assertThat(cache.get(1))
            .isNull()

        assertThat(cache.get(2))
            .isNull()
    }
}
