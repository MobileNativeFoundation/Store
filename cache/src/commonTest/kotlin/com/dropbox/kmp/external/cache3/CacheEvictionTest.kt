package com.dropbox.kmp.external.cache3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CacheEvictionTest {

    @Test
    fun maxSizeLimitReached_addNewEntry_oldEntryEvicted() {
        val cache = cacheBuilder<Long, String> {
            maximumSize { 2 }
        }

        cache.put(1, "dog")
        cache.put(2, "cat")

        // this exceeds the max size limit
        cache.put(3, "bird")

        assertNull(cache.getIfPresent(1))
        assertEquals("cat", cache.getIfPresent(2))
        assertEquals("bird", cache.getIfPresent(3))

        // this exceeds the max size limit again
        cache.put(4, "dinosaur")

        assertNull(cache.getIfPresent(1))
        assertNull(cache.getIfPresent(2))
        assertEquals("bird", cache.getIfPresent(3))
        assertEquals("dinosaur", cache.getIfPresent(4))
    }

    @Test
    fun maxSizeLimitReached_replaceCacheEntry_doesNotEvict() {
        val cache = cacheBuilder<Long, String> {
            maximumSize { 2 }
        }

        cache.put(1, "dog")
        cache.put(2, "cat")

        // replacing an entry does not change internal cache size
        cache.put(2, "bird")
        cache.put(2, "dinosaur")

        assertEquals("dog", cache.getIfPresent(1))
        assertEquals("dinosaur", cache.getIfPresent(2))
    }

    @Test
    fun readCacheEntry_accessOrderChanged() {
        val cache = cacheBuilder<Long, String> {
            maximumSize { 3 }
        }
        cache.put(1, "dog")
        cache.put(2, "cat")
        cache.put(3, "bird")

        // read 1st entry - access order becomes 2, 3, 1
        cache.getIfPresent(1)

        // add a new entry
        cache.put(4, "dinosaur")

        // 2nd entry should be evicted
        assertEquals("dog", cache.getIfPresent(1))
        assertNull(cache.getIfPresent(2))
        assertEquals("bird", cache.getIfPresent(3))
        assertEquals("dinosaur", cache.getIfPresent(4))
    }

    @Test
    fun replaceCacheValue_accessOrderChanged() {
        val cache = cacheBuilder<Long, String> {
            maximumSize { 3 }
        }

        cache.put(1, "dog")
        cache.put(2, "cat")
        cache.put(3, "bird")

        // replace 1st entry - access order becomes 2, 3, 1
        cache.put(1, "rabbit")

        // replace 2nd entry - access order becomes 3, 1, 2
        cache.put(2, "fish")

        // add a new entry
        cache.put(4, "dinosaur")

        // 3rd entry should be evicted
        assertEquals("rabbit", cache.getIfPresent(1))
        assertEquals("fish", cache.getIfPresent(2))
        assertNull(cache.getIfPresent(3))
        assertEquals("dinosaur", cache.getIfPresent(4))
    }

    @Test
    fun maximumCacheSizeIsOne_addNewEntry_existingEntryEvicted() {
        val cache = cacheBuilder<Long, String> {
            maximumSize { 1 }
        }

        cache.put(1, "dog")

        assertEquals("dog", cache.getIfPresent(1))

        cache.put(2, "cat")

        assertNull(cache.getIfPresent(1))

        assertEquals("cat", cache.getIfPresent(2))

        cache.put(1, "dog")

        assertEquals("dog", cache.getIfPresent(1))
        assertNull(cache.getIfPresent(2))
    }

    @Test
    fun maximumCacheSizeIsZero_noValuesCached() {
        val cache = cacheBuilder<Long, String> {
            maximumSize { 0 }
        }

        cache.put(1, "dog")
        cache.put(2, "cat")

        assertNull(cache.getIfPresent(1))
        assertNull(cache.getIfPresent(2))
    }
}
