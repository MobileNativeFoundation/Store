package com.dropbox.android.external.cache4

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

        assertEquals("dog", cache.get(1))

        assertEquals("cat", cache.get(2))
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

        assertEquals("dog", cache.get(1))

        // now expires
        clock.virtualDuration = 1.minutes

        assertNull(cache.get(1))
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

        assertEquals("cat", cache.get(1))

        // should now expire
        clock.virtualDuration = 1.minutes * 2 - 1.nanoseconds

        assertNull(cache.get(1))
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
        assertEquals("dog", cache.get(1))

        // should expire despite cache just being read
        clock.virtualDuration = 1.minutes

        assertNull(cache.get(1))
    }

    @Test
    fun `cache entry gets evicted when expired after access`() {
        val cache = Cache.Builder.newBuilder()
            .clock(clock)
            .expireAfterAccess(2.minutes)
            .build<Long, String>()

        cache.put(1, "dog")

        // read cache immediately
        assertEquals("dog", cache.get(1))

        // now expires
        clock.virtualDuration = 2.minutes

        assertNull(cache.get(1))
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

        assertEquals("cat", cache.get(1))

        // should now expire
        clock.virtualDuration = 2.minutes * 2

        assertNull(cache.get(1))
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
        assertEquals("dog", cache.get(1))

        // should not expire yet as cache was just read (accessed)
        clock.virtualDuration = 2.minutes

        assertEquals("dog", cache.get(1))

        // should now expire
        clock.virtualDuration = 2.minutes * 2

        assertNull(cache.get(1))
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

        assertNull(cache.get(1))

        // cache a new value
        cache.put(1, "cat")

        // before new access expiry
        clock.virtualDuration = 2.minutes - 1.nanoseconds

        // this should resets access expiry time but not write expiry time
        assertEquals("cat", cache.get(1))

        // should now expire due to write expiry
        clock.virtualDuration = (1.minutes + 2.minutes - 1.nanoseconds)

        assertNull(cache.get(1))
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

        assertNull(cache.get(1))

        assertNull(cache.get(2))

        assertEquals("bird", cache.get(3))

        // just before 3rd entry expires
        clock.virtualDuration = 1.minutes + 1.minutes / 2 - 1.nanoseconds

        assertEquals("bird", cache.get(3))

        // 3rd entry should now expire
        clock.virtualDuration = 1.minutes + 1.minutes / 2

        assertNull(cache.get(3))
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

        assertNull(cache.get(1))

        assertEquals("cat", cache.get(2))

        assertEquals("bird", cache.get(3))
    }
}
