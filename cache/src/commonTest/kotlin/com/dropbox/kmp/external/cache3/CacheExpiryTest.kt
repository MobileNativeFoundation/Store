package com.dropbox.kmp.external.cache3


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.ExperimentalTime

@ExperimentalTime
class CacheExpiryTest {

    private val fakeTicker = FakeTicker()

    @Test
    fun noWriteOrAccessExpiry_cacheNeverExpires() {
        val cache = cacheBuilder<Long, String> {
            ticker { fakeTicker.ticker }
        }
        cache.put(1, "dog")
        cache.put(2, "cat")

        assertEquals("dog", cache.getIfPresent(1))
        assertEquals("cat", cache.getIfPresent(2))
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun expiredAfterWrite_cacheEntryEvicted() {
        val cache = cacheBuilder<Long, String> {
            ticker { fakeTicker.ticker }
            expireAfterWrite { 1.minutes }
        }

        cache.put(1, "dog")

        // just before expiry
        fakeTicker += 1.minutes - 1.nanoseconds

        assertEquals("dog", cache.getIfPresent(1))

        // now expires
        fakeTicker += 1.nanoseconds

        assertNull(cache.getIfPresent(1))
    }

    @Test
    fun replaceCacheValue_writeExpiryTimeReset() {
        val cache = cacheBuilder<Long, String> {
            ticker { fakeTicker.ticker }
            expireAfterWrite { 1.minutes }
        }

        cache.put(1, "dog")

        // just before expiry
        fakeTicker += 1.minutes - 1.nanoseconds

        // update cache
        cache.put(1, "cat")

        // should not expire yet as cache was just updated
        fakeTicker += 1.nanoseconds

        assertEquals("cat", cache.getIfPresent(1))

        // should now expire
        fakeTicker += 1.minutes - 1.nanoseconds

        assertNull(cache.getIfPresent(1))
    }

    @Test
    fun readCacheEntry_doesNotResetWriteExpiryTime() {
        val cache = cacheBuilder<Long, String> {
            ticker { fakeTicker.ticker }
            expireAfterWrite { 1.minutes }
        }

        cache.put(1, "dog")

        // just before expiry
        fakeTicker += 1.minutes - 1.nanoseconds

        // read cache before expected write expiry
        assertEquals("dog", cache.getIfPresent(1))

        // should expire despite cache just being read
        fakeTicker += 1.nanoseconds

        assertNull(cache.getIfPresent(1))
    }

    @Test
    fun expiredAfterAccess_cacheEntryEvicted() {
        val cache = cacheBuilder<Long, String> {
            ticker { fakeTicker.ticker }
            expireAfterAccess { 2.minutes }
        }

        cache.put(1, "dog")

        // read cache immediately
        assertEquals("dog", cache.getIfPresent(1))

        // now expires
        fakeTicker += 2.minutes

        assertNull(cache.getIfPresent(1))
    }

    @Test
    fun replaceCacheValue_accessExpiryTimeReset() {
        val cache = cacheBuilder<Long, String> {
            ticker { fakeTicker.ticker }
            expireAfterAccess { 2.minutes }
        }

        cache.put(1, "dog")

        // just before expiry
        fakeTicker += 2.minutes - 1.nanoseconds

        // update cache
        cache.put(1, "cat")

        // should not expire yet as cache was just updated
        fakeTicker += 1.nanoseconds

        assertEquals("cat", cache.getIfPresent(1))

        // should now expire
        fakeTicker += 2.minutes

        assertNull(cache.getIfPresent(1))
    }

    @Test
    fun readCacheEntry_accessExpiryTimeReset() {
        val cache = cacheBuilder<Long, String> {
            ticker { fakeTicker.ticker }
            expireAfterAccess { 2.minutes }
        }

        cache.put(1, "dog")

        // just before expiry
        fakeTicker += 2.minutes - 1.nanoseconds

        // read cache before expected access expiry
        assertEquals("dog", cache.getIfPresent(1))

        // should not expire yet as cache was just read (accessed)
        fakeTicker += 1.nanoseconds

        assertEquals("dog", cache.getIfPresent(1))

        // should now expire
        fakeTicker += 2.minutes

        assertNull(cache.getIfPresent(1))
    }

    @Test
    fun expiryRespectsBothExpireAfterWriteAndExpireAfterAccess() {

        val cache = cacheBuilder<Long, String> {
            ticker { fakeTicker.ticker }
            expireAfterWrite { 2.minutes }
            expireAfterAccess { 1.minutes }
        }
        cache.put(1, "dog")

        // expires due to access expiry
        fakeTicker += 1.minutes

        assertNull(cache.getIfPresent(1))

        // cache a new value
        cache.put(1, "cat")

        // before new access expiry
        fakeTicker += 1.minutes - 1.nanoseconds

        // this should reset access expiry time but not write expiry time
        assertEquals("cat", cache.getIfPresent(1))

        // should now expire due to write expiry
        fakeTicker += 1.minutes

        assertNull(cache.getIfPresent(1))
    }

    @Test
    fun onlyExpiredCacheEntriesAreEvicted() {
        val cache = cacheBuilder<Long, String> {
            ticker { fakeTicker.ticker }
            expireAfterWrite { 1.minutes }
        }
        cache.put(1, "dog")
        cache.put(2, "cat")

        // cache a new value
        fakeTicker += 1.minutes / 2
        cache.put(3, "bird")

        // now first 2 entries should expire, 3rd entry should not expire yet
        fakeTicker += 1.minutes / 2

        assertNull(cache.getIfPresent(1))
        assertNull(cache.getIfPresent(2))
        assertEquals("bird", cache.getIfPresent(3))

        // just before 3rd entry expires
        fakeTicker += 1.minutes / 2 - 1.nanoseconds

        assertEquals("bird", cache.getIfPresent(3))

        // 3rd entry should now expire
        fakeTicker += 1.nanoseconds

        assertNull(cache.getIfPresent(3))
    }

    @Test
    fun maxSizeLimitExceededBeforeExpectedExpiry_cacheEntryEvicted() {
        val cache = cacheBuilder<Long, String> {
            ticker { fakeTicker.ticker }
            maximumSize { 2 }
            expireAfterWrite { 1.minutes }
        }

        cache.put(1, "dog")
        cache.put(2, "cat")

        // add a new cache entry before first entry is expected to expire
        fakeTicker += 1.minutes / 2
        cache.put(3, "bird")

        // first entry should be evicted despite not being expired
        assertNull(cache.getIfPresent(1))
        assertEquals("cat", cache.getIfPresent(2))
        assertEquals("bird", cache.getIfPresent(3))
    }
}