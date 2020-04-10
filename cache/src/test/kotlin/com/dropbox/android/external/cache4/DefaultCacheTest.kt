package com.dropbox.android.external.cache4

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class DefaultCacheTest {

    @Test
    fun `get(key) returns null when no entry with the associated key exists`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        assertNull(cache.get(1))
    }

    @Test
    fun `get(key) returns value when entry with the associated key exists`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        assertEquals("dog", cache.get(1))

        assertEquals("cat", cache.get(2))
    }

    @Test
    fun `calling get(key) multiple times return the same value`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        cache.put(1, "dog")

        val value1 = cache.get(1)
        val value2 = cache.get(1)

        assertEquals("dog", value1)

        assertEquals("dog", value2)
    }

    @Test
    fun `get(key) returns latest value when values have been replaced for the same key`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")
        cache.put(1, "bird")

        assertEquals("bird", cache.get(1))

        assertEquals("cat", cache.get(2))
    }

    @Test
    fun `can cache complex type value with hashcode`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, TypeWithHashCode>()

        cache.put(1, TypeWithHashCode("dog", 10))
        cache.put(2, TypeWithHashCode("cat", 15))

        assertEquals(TypeWithHashCode("dog", 10), cache.get(1))

        assertEquals(TypeWithHashCode("cat", 15), cache.get(2))
    }

    @Test
    fun `can cache complex type value without hashcode`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, TypeWithoutHashCode>()

        cache.put(1, TypeWithoutHashCode("dog", 10))

        val value = cache.get(1)

        assertEquals("dog", value?.x)

        assertEquals(10, value?.y)
    }

    @Test
    fun `can cache with complex type key with hashcode`() {
        val cache = Cache.Builder.newBuilder()
            .build<TypeWithHashCode, String>()

        cache.put(TypeWithHashCode("a", 1), "dog")

        val value = cache.get(TypeWithHashCode("a", 1))

        assertEquals("dog", value)
    }

    @Test
    fun `can cache with complex type key without hashcode`() {
        val cache = Cache.Builder.newBuilder()
            .build<TypeWithoutHashCode, String>()

        val key = TypeWithoutHashCode("a", 1)

        cache.put(key, "dog")

        val value1 = cache.get(TypeWithoutHashCode("a", 1))
        val value2 = cache.get(key)

        assertNull(value1)

        assertEquals("dog", value2)
    }

    @Test
    fun `can cache same value with different keys`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, TypeWithoutHashCode>()

        val valueToCache = TypeWithoutHashCode("dog", 10)

        cache.put(1, valueToCache)
        cache.put(2, valueToCache)

        assertEquals(valueToCache, cache.get(1))

        assertEquals(valueToCache, cache.get(2))
    }

    @Test
    fun `can cache using Unit as key`() {
        val cache = Cache.Builder.newBuilder()
            .build<Unit, String>()

        cache.put(Unit, "dog")
        cache.put(Unit, "cat")

        assertEquals("cat", cache.get(Unit))
    }

    @Test
    fun `asMap() returns all entries`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        assertEquals(mapOf(1L to "dog", 2L to "cat"), cache.asMap())
    }

    @Test
    fun `asMap() creates a defensive copy`() {
        val cache = Cache.Builder.newBuilder()
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        val map = cache.asMap() as MutableMap
        map[3] = "bird"

        assertNull(cache.get(3))
    }
}
