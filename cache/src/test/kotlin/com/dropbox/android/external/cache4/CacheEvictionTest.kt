package com.dropbox.android.external.cache4

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.time.ExperimentalTime

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

        assertThat(cache.get(1))
            .isNull()

        assertThat(cache.get(2))
            .isEqualTo("cat")

        assertThat(cache.get(3))
            .isEqualTo("bird")

        // this exceeds the max size limit again
        cache.put(4, "dinosaur")

        assertThat(cache.get(1))
            .isNull()

        assertThat(cache.get(2))
            .isNull()

        assertThat(cache.get(3))
            .isEqualTo("bird")

        assertThat(cache.get(4))
            .isEqualTo("dinosaur")
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

        assertThat(cache.get(1))
            .isEqualTo("dog")

        assertThat(cache.get(2))
            .isEqualTo("dinosaur")
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
        assertThat(cache.get(1))
            .isEqualTo("dog")

        assertThat(cache.get(2))
            .isNull()

        assertThat(cache.get(3))
            .isEqualTo("bird")

        assertThat(cache.get(4))
            .isEqualTo("dinosaur")
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
        assertThat(cache.get(1))
            .isEqualTo("rabbit")

        assertThat(cache.get(2))
            .isEqualTo("fish")

        assertThat(cache.get(3))
            .isNull()

        assertThat(cache.get(4))
            .isEqualTo("dinosaur")
    }

    @Test
    fun `adding a new entry always evicts existing entry when maximumCacheSize is set to 1`() {
        val cache = Cache.Builder.newBuilder()
            .maximumCacheSize(1)
            .build<Long, String>()

        cache.put(1, "dog")

        assertThat(cache.get(1))
            .isEqualTo("dog")

        cache.put(2, "cat")

        assertThat(cache.get(1))
            .isNull()

        assertThat(cache.get(2))
            .isEqualTo("cat")

        cache.put(1, "dog")

        assertThat(cache.get(1))
            .isEqualTo("dog")

        assertThat(cache.get(2))
            .isNull()
    }

    @Test
    fun `no values are cached when maximumCacheSize is set to 0`() {
        val cache = Cache.Builder.newBuilder()
            .maximumCacheSize(0)
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        assertThat(cache.get(1))
            .isNull()

        assertThat(cache.get(2))
            .isNull()
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
}
