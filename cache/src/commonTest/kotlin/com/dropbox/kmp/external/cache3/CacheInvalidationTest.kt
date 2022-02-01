package com.dropbox.kmp.external.cache3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

class CacheInvalidationTest {

    @Test
    fun invalidateByKey_associatedEntryEvicted() {
        val cache = cacheBuilder<Long, String> { }

        cache.put(1, "dog")
        cache.put(2, "cat")

        cache.invalidate(2)

        assertEquals("dog", cache.getIfPresent(1))
        assertNull(cache.getIfPresent(2))
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun invalidateByKey_allExpiredEntriesEvicted() {
        val mutableTicker = MutableTicker()
        val oneMinute = 1.minutes
        val cache = cacheBuilder<Long, String> {
            ticker(mutableTicker.ticker)
            expireAfterWrite(oneMinute)
        }

        cache.put(1, "dog")
        cache.put(2, "cat")

        mutableTicker += oneMinute / 2

        cache.put(3, "bird")

        // first 2 entries now expire
        mutableTicker += oneMinute / 2

        cache.invalidate(3)

        // all 3 entries should have been evicted
        assertNull(cache.getIfPresent(1))
        assertNull(cache.getIfPresent(2))
        assertNull(cache.getIfPresent(3))
    }

    @Test
    fun invalidateAll_allEntriesEvicted() {
        val cache = cacheBuilder<Long, String> { }

        cache.put(1, "dog")
        cache.put(2, "cat")
        cache.put(3, "bird")

        assertEquals("dog", cache.getIfPresent(1))
        assertEquals("cat", cache.getIfPresent(2))
        assertEquals("bird", cache.getIfPresent(3))

        cache.invalidateAll()

        assertNull(cache.getIfPresent(1))
        assertNull(cache.getIfPresent(2))
        assertNull(cache.getIfPresent(3))
    }
}
