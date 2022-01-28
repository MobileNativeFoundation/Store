package com.dropbox.kmp.external.cache3


import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DefaultCacheTest {

    @Test
    fun noEntryWithAssociatedKeyExists_get_returnsNull() {
        val cache = cacheBuilder<Long, String> { }

        assertNull(cache.getIfPresent(1))
    }

    @Test
    fun entryWithAssociatedKeyExists_get_returnsValue() {
        val cache = cacheBuilder<Long, String> { }

        cache.put(1, "dog")
        cache.put(2, "cat")

        assertEquals("dog", cache.getIfPresent(1))
        assertEquals("cat", cache.getIfPresent(2))
    }

    @Test
    fun getMultipleTimesWithSameKey_returnsSameValue() {
        val cache = cacheBuilder<Long, String> { }

        cache.put(1, "dog")

        val value1 = cache.getIfPresent(1)
        val value2 = cache.getIfPresent(1)

        assertEquals("dog", value1)
        assertEquals("dog", value2)
    }

    @Test
    fun valuesReplacedForSameKey_get_returnsLatestValue() {
        val cache = cacheBuilder<Long, String> { }

        cache.put(1, "dog")
        cache.put(2, "cat")
        cache.put(1, "bird")

        assertEquals("bird", cache.getIfPresent(1))
        assertEquals("cat", cache.getIfPresent(2))
    }

    @Test
    fun cacheComplexTypeValueWithHashCode() {
        val cache = cacheBuilder<Long, TypeWithHashCode> { }

        cache.put(1, TypeWithHashCode("dog", 10))
        cache.put(2, TypeWithHashCode("cat", 15))

        assertEquals(TypeWithHashCode("dog", 10), cache.getIfPresent(1))
        assertEquals(TypeWithHashCode("cat", 15), cache.getIfPresent(2))
    }

    @Test
    fun cacheComplexTypeValueWithoutHashCode() {
        val cache = cacheBuilder<Long, TypeWithoutHashCode> { }

        cache.put(1, TypeWithoutHashCode("dog", 10))

        val value = cache.getIfPresent(1)

        assertEquals("dog", value?.x)
        assertEquals(10, value?.y)
    }

    @Test
    fun cacheWithComplexTypeKeyWithHashcode() {
        val cache = cacheBuilder<TypeWithHashCode, String> { }

        cache.put(TypeWithHashCode("a", 1), "dog")

        val value = cache.getIfPresent(TypeWithHashCode("a", 1))

        assertEquals("dog", value)
    }

    @Test
    fun cacheWithComplexTypeKeyWithoutHashcode() {
        val cache = cacheBuilder<TypeWithoutHashCode, String> { }

        val key = TypeWithoutHashCode("a", 1)

        cache.put(key, "dog")

        val value1 = cache.getIfPresent(TypeWithoutHashCode("a", 1))
        val value2 = cache.getIfPresent(key)

        assertNull(value1)
        assertEquals("dog", value2)
    }

    @Test
    fun cacheWithSameValueAndDifferentKeys() {
        val cache = cacheBuilder<Long, TypeWithoutHashCode> { }

        val valueToCache = TypeWithoutHashCode("dog", 10)

        cache.put(1, valueToCache)
        cache.put(2, valueToCache)

        assertEquals(valueToCache, cache.getIfPresent(1))
        assertEquals(valueToCache, cache.getIfPresent(2))
    }

    @Test
    fun cacheUsingUnitAsKey() {
        val cache = cacheBuilder<Unit, String> { }

        cache.put(Unit, "dog")
        cache.put(Unit, "cat")

        assertEquals("cat", cache.getIfPresent(Unit))
    }

}