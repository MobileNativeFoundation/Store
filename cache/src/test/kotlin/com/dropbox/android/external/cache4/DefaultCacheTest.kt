package com.dropbox.android.external.cache4

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultCacheTest {

    @Test
    fun `get(key) returns null when no entry with the associated key exists`() {
        val cache = CacheBuilder()
            .build<Long, String>()

        assertThat(cache.get(1))
            .isNull()
    }

    @Test
    fun `get(key) returns value when entry with the associated key exists`() {
        val cache = CacheBuilder()
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")

        assertThat(cache.get(1))
            .isEqualTo("dog")

        assertThat(cache.get(2))
            .isEqualTo("cat")
    }

    @Test
    fun `calling get(key) multiple times return the same value`() {
        val cache = CacheBuilder()
            .build<Long, String>()

        cache.put(1, "dog")

        val value1 = cache.get(1)
        val value2 = cache.get(1)

        assertThat(value1)
            .isEqualTo("dog")

        assertThat(value2)
            .isEqualTo("dog")
    }

    @Test
    fun `get(key) returns latest value when values have been replaced for the same key`() {
        val cache = CacheBuilder()
            .build<Long, String>()

        cache.put(1, "dog")
        cache.put(2, "cat")
        cache.put(1, "bird")

        assertThat(cache.get(1))
            .isEqualTo("bird")

        assertThat(cache.get(2))
            .isEqualTo("cat")
    }

    @Test
    fun `can cache complex type value with hashcode`() {
        val cache = CacheBuilder()
            .build<Long, TypeWithHashCode>()

        cache.put(1, TypeWithHashCode("dog", 10))
        cache.put(2, TypeWithHashCode("cat", 15))

        assertThat(cache.get(1))
            .isEqualTo(TypeWithHashCode("dog", 10))

        assertThat(cache.get(2))
            .isEqualTo(TypeWithHashCode("cat", 15))
    }

    @Test
    fun `can cache complex type value without hashcode`() {
        val cache = CacheBuilder()
            .build<Long, TypeWithoutHashCode>()

        cache.put(1, TypeWithoutHashCode("dog", 10))

        val value = cache.get(1)

        assertThat(value)
            .isNotEqualTo(TypeWithHashCode("dog", 10))

        assertThat(value?.x)
            .isEqualTo("dog")

        assertThat(value?.y)
            .isEqualTo(10)
    }

    @Test
    fun `can cache with complex type key with hashcode`() {
        val cache = CacheBuilder()
            .build<TypeWithHashCode, String>()

        cache.put(TypeWithHashCode("a", 1), "dog")

        val value = cache.get(TypeWithHashCode("a", 1))

        assertThat(value)
            .isEqualTo("dog")
    }

    @Test
    fun `can cache with complex type key without hashcode`() {
        val cache = CacheBuilder()
            .build<TypeWithoutHashCode, String>()

        val key = TypeWithoutHashCode("a", 1)

        cache.put(key, "dog")

        val value1 = cache.get(TypeWithoutHashCode("a", 1))
        val value2 = cache.get(key)

        assertThat(value1)
            .isNull()

        assertThat(value2)
            .isEqualTo("dog")
    }

    @Test
    fun `can cache same value with different keys`() {
        val cache = CacheBuilder()
            .build<Long, TypeWithoutHashCode>()

        val valueToCache = TypeWithoutHashCode("dog", 10)

        cache.put(1, valueToCache)
        cache.put(2, valueToCache)

        assertThat(cache.get(1))
            .isEqualTo(valueToCache)

        assertThat(cache.get(2))
            .isEqualTo(valueToCache)
    }

    @Test
    fun `can cache using Unit as key`() {
        val cache = CacheBuilder()
            .build<Unit, String>()

        cache.put(Unit, "dog")
        cache.put(Unit, "cat")

        assertThat(cache.get(Unit))
            .isEqualTo("cat")
    }
}
