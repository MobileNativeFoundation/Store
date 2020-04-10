package com.dropbox.android.external.cache4

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.minutes
import kotlin.time.nanoseconds

@ExperimentalTime
class CacheInvalidationTest {

    @Test
    fun `calling invalidate(key) evicts the entry associated with the key`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        cache.invalidate(2)

        assertEquals("dog", cache.get(1))

        assertNull(cache.get(2))
    }

    @Test
    fun `calling invalidate(key) also evicts all expired entries`() {
        val clock = TestClock(virtualDuration = 0.nanoseconds)
        val oneMinute = 1.minutes

        val cache = Cache.Builder.newBuilder()
            .clock(clock)
            .expireAfterWrite(oneMinute)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        clock.virtualDuration = oneMinute / 2

        cache.put(3, "bird")

        // first 2 entries now expire
        clock.virtualDuration = oneMinute

        cache.invalidate(3)

        // all 3 entries should have been evicted
        assertNull(cache.get(1))

        assertNull(cache.get(2))

        assertNull(cache.get(3))
    }

    @Test
    fun `calling invalidateAll() evicts all entries in the cache`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")
        cache.put(3, "bird")

        assertEquals("dog", cache.get(1))

        assertEquals("cat", cache.get(2))

        assertEquals("bird", cache.get(3))

        cache.invalidateAll()

        assertNull(cache.get(1))

        assertNull(cache.get(2))

        assertNull(cache.get(3))
    }
}
