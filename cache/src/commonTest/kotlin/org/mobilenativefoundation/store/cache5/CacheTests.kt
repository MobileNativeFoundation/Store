package org.mobilenativefoundation.store.cache5

import kotlinx.coroutines.test.runTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class CacheTests {
    private val cache: Cache<String, String> = CacheBuilder<String, String>().build()

    @Test
    fun getIfPresent() {
        cache.put("key", "value")
        assertEquals("value", cache.getIfPresent("key"))
    }

    @Test
    fun getOrPut() {
        assertEquals("value", cache.getOrPut("key") { "value" })
    }

    @Ignore // Not implemented yet
    @Test
    fun getAllPresent() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        assertEquals(mapOf("key1" to "value1", "key2" to "value2"), cache.getAllPresent(listOf("key1", "key2")))
    }

    @Ignore // Not implemented yet
    @Test
    fun putAll() {
        cache.putAll(mapOf("key1" to "value1", "key2" to "value2"))
        assertEquals(mapOf("key1" to "value1", "key2" to "value2"), cache.getAllPresent(listOf("key1", "key2")))
    }

    @Test
    fun invalidate() {
        cache.put("key", "value")
        cache.invalidate("key")
        assertEquals(null, cache.getIfPresent("key"))
    }

    @Ignore // Not implemented yet
    @Test
    fun invalidateAll() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        cache.invalidateAll(listOf("key1", "key2"))
        assertEquals(null, cache.getIfPresent("key1"))
        assertEquals(null, cache.getIfPresent("key2"))
    }

    @Ignore // Not implemented yet
    @Test
    fun size() {
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        assertEquals(2, cache.size())
    }

    @Test
    fun maximumSize() {
        val cache = CacheBuilder<String, String>().maximumSize(1).build()
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        assertEquals(null, cache.getIfPresent("key1"))
        assertEquals("value2", cache.getIfPresent("key2"))
    }

    @Test
    fun maximumWeight() {
        val cache = CacheBuilder<String, String>().weigher(399) { _, _ -> 100 }.build()
        cache.put("key1", "value1")
        cache.put("key2", "value2")
        assertEquals(null, cache.getIfPresent("key1"))
        assertEquals("value2", cache.getIfPresent("key2"))
    }

    @Test
    fun expireAfterAccess() =
        runTest {
            var timeNs = 0L
            val cache = CacheBuilder<String, String>().expireAfterAccess(100.milliseconds).ticker { timeNs }.build()
            cache.put("key", "value")

            timeNs += 50.milliseconds.inWholeNanoseconds
            assertEquals("value", cache.getIfPresent("key"))

            timeNs += 100.milliseconds.inWholeNanoseconds
            assertEquals(null, cache.getIfPresent("key"))
        }

    @Test
    fun expireAfterWrite() =
        runTest {
            var timeNs = 0L
            val cache = CacheBuilder<String, String>().expireAfterWrite(100.milliseconds).ticker { timeNs }.build()
            cache.put("key", "value")

            timeNs += 50.milliseconds.inWholeNanoseconds
            assertEquals("value", cache.getIfPresent("key"))

            timeNs += 50.milliseconds.inWholeNanoseconds
            assertEquals(null, cache.getIfPresent("key"))
        }
}
