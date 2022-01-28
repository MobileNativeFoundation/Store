package com.dropbox.kmp.external.cache3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.ExperimentalTime

@ExperimentalTime
class CacheDefaultValueTest {

    private val fakeTicker = FakeTicker()
    private val expiryDuration = 1.minutes

    @Test
    fun entryWithAssociatedKeyNotExists_getWithDefaultValueFactory_returnsValueFromDefaultValueFactory() {
        val cache = cacheBuilder<Long, String> { }

        var factoryInvokeCount = 0
        val defaultValueFactory = {
            factoryInvokeCount++
            "dog"
        }

        val value = cache.getOrPut(1, defaultValueFactory)

        assertEquals(1, factoryInvokeCount)
        assertEquals("dog", value)
    }

    @Test
    fun expiredEntryWithAssociatedKeyExists_getWithDefaultValueFactory_returnsValueFromDefaultValueFactory() {
        val cache = cacheBuilder<Long, String> {
            expireAfterWrite { expiryDuration }
            ticker { fakeTicker.ticker }
        }

        cache.put(1, "cat")

        // now expires
        fakeTicker += expiryDuration

        var defaultValueFactoryInvokeCount = 0
        val defaultValueFactory = {
            defaultValueFactoryInvokeCount++
            "dog"
        }

        val value = cache.getOrPut(1, defaultValueFactory)

        assertEquals(1, defaultValueFactoryInvokeCount)
        assertEquals("dog", value)
    }

    @Test
    fun unexpiredEntryWithAssociatedKeyExists_getWithDefaultValueFactory_returnsExistingValue() {
        val cache = cacheBuilder<Long, String> {
            expireAfterAccess { expiryDuration }
            ticker { fakeTicker.ticker }
        }

        cache.put(1, "dog")

        // just before expiry
        fakeTicker += expiryDuration - 1.nanoseconds

        var defaultValueFactoryInvokeCount = 0
        val defaultValueFactory = {
            defaultValueFactoryInvokeCount++
            "cat"
        }

        val value = cache.getOrPut(1, defaultValueFactory)

        assertEquals(0, defaultValueFactoryInvokeCount)
        assertEquals("dog", value)
    }

    @Test
    fun defaultValueFactoryThrowsException_getWithDefaultValueFactory_exceptionPropagated() {
        val cache = cacheBuilder<Long, String> { }

        var defaultValueFactoryInvokeCount = 0
        val defaultValueFactory = {
            defaultValueFactoryInvokeCount++
            throw IllegalStateException()
        }

        assertFailsWith<IllegalStateException> {
            cache.getOrPut(1, defaultValueFactory)
        }

        assertEquals(1, defaultValueFactoryInvokeCount)
        assertNull(cache.getIfPresent(1))
    }
}