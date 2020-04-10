package com.dropbox.android.external.cache4

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.minutes

@ExperimentalTime
class CacheEvictionTest {

    @Test
    fun `an old entry should be evicted when adding a new entry exceeds the max size limit`() {
        val cache = Cache.Builder.newBuilder()
            .maximumCacheSize(2)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        // this exceeds the max size limit
        cache.put(3, "bird")

        assertNull(cache.get(1))

        assertEquals("cat", cache.get(2))

        assertEquals("bird", cache.get(3))

        // this exceeds the max size limit again
        cache.put(4, "dinosaur")

        assertNull(cache.get(1))

        assertNull(cache.get(2))

        assertEquals("bird", cache.get(3))

        assertEquals("dinosaur", cache.get(4))
    }

    @Test
    fun `replacing a cache entry does not grow the cache size`() {
        val cache = Cache.Builder.newBuilder()
            .maximumCacheSize(2)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        // replacing an entry does not change internal cache size
        cache.put(2, "bird")
        cache.put(2, "dinosaur")

        assertEquals("dog", cache.get(1))

        assertEquals("dinosaur", cache.get(2))
    }

    @Test
    fun `size-based eviction follows LRU - reading a cache entry changes access order`() {
        val cache = Cache.Builder.newBuilder()
            .maximumCacheSize(3)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")
        cache.put(3, "bird")

        // read 1st entry - access order becomes 2, 3, 1
        cache.get(1)

        // add a new entry
        cache.put(4, "dinosaur")

        // 2nd entry should be evicted
        assertEquals("dog", cache.get(1))

        assertNull(cache.get(2))

        assertEquals("bird", cache.get(3))

        assertEquals("dinosaur", cache.get(4))
    }

    @Test
    fun `size-based eviction follows LRU - replacing a cache value changes access order`() {
        val cache = Cache.Builder.newBuilder()
            .maximumCacheSize(3)
            .build<Long, String>()

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
        assertEquals("rabbit", cache.get(1))

        assertEquals("fish", cache.get(2))

        assertNull(cache.get(3))

        assertEquals("dinosaur", cache.get(4))
    }

    @Test
    fun `adding a new entry always evicts existing entry when maximumCacheSize is set to 1`() {
        val cache = Cache.Builder.newBuilder()
            .maximumCacheSize(1)
            .build<Long, String>()

        cache.put(1, "dog")

        assertEquals("dog", cache.get(1))

        cache.put(2, "cat")

        assertNull(cache.get(1))

        assertEquals("cat", cache.get(2))

        cache.put(1, "dog")

        assertEquals("dog", cache.get(1))

        assertNull(cache.get(2))
    }

    @Test
    fun `no values are cached when maximumCacheSize is set to 0`() {
        val cache = Cache.Builder.newBuilder()
            .maximumCacheSize(0)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        assertNull(cache.get(1))

        assertNull(cache.get(2))
    }

    @Test
    fun `cache entry eviction can happen concurrently`() = runBlocking {
        val cache = Cache.Builder.newBuilder()
            .maximumCacheSize(2)
            .build<Long, String>()

        // this should not produce a ConcurrentModificationException
        repeat(20) {
            launch(newSingleThreadDispatcher()) {
                cache.put(it.toLong(), "value for $it")
            }
        }
    }

    @Test
    fun `size-based eviction can complete when expireAfterWrite is set`() {
        val cache = Cache.Builder.newBuilder()
            .expireAfterAccess(1.minutes)
            .maximumCacheSize(1)
            .build<Int, String>()

        cache.put(0, "dog")

        // accessing cache
        cache.get(0)

        // evict
        cache.put(1, "cat")

        // evict again
        cache.put(2, "bird")

        assertNull(cache.get(0))

        assertNull(cache.get(1))

        assertEquals("bird", cache.get(2))
    }
}
