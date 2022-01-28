package com.dropbox.kmp.external.cache3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.nanoseconds

class CacheBuilderTest {

    @Test
    fun expireAfterWrite_positiveDuration() {
        val cacheBuilder = CacheBuilder<Any, Any>().apply {
            expireAfterWrite { 24.hours }
        }
        assertEquals(24.hours, cacheBuilder.expireAfterWrite)
    }

    @Test
    fun expireAfterWrite_negativeDuration() {
        val exception = assertFailsWith<IllegalArgumentException> {
            cacheBuilder<Any, Any> {
                expireAfterWrite { -(1.nanoseconds) }
            }
        }
        assertEquals("expireAfterWrite duration must be positive", exception.message)
    }

    @Test
    fun expireAfterAccess_positiveDuration() {
        val cacheBuilder = CacheBuilder<Any, Any>().apply {
            expireAfterAccess { 24.hours }
        }
        assertEquals(24.hours, cacheBuilder.expireAfterAccess)
    }

    @Test
    fun expireAfterAccess_negativeDuration() {
        val exception = assertFailsWith<IllegalArgumentException> {
            cacheBuilder<Any, Any> {
                expireAfterAccess { -(1.nanoseconds) }
            }
        }
        assertEquals("expireAfterAccess duration must be positive", exception.message)
    }

    @Test
    fun customWeigher() {
        val customWeigher: Weigher<Any, Any> = { _, _ -> 1 }
        val cacheBuilder = CacheBuilder<Any, Any>().apply {
            weigher(1, customWeigher)
        }

        assertEquals(1, cacheBuilder.maximumWeight)
        assertEquals(customWeigher, cacheBuilder.weigher)
    }

    @Test
    fun maxiumumWeigh_negative() {
        val customWeigher: Weigher<Any, Any> = { _, _ -> 1 }
        val exception = assertFailsWith<IllegalArgumentException> {
            cacheBuilder<Any, Any> {
                weigher(-1, customWeigher)
            }
        }
        assertEquals("maximum weight must not be negative", exception.message)
    }

    @Test
    fun maximumCacheSize_zero() {
        val cacheBuilder = CacheBuilder<Any, Any>().apply {
            maximumSize { 0 }
        }

        assertEquals(0, cacheBuilder.maximumSize)
    }

    @Test
    fun maximumCacheSize_positiveValue() {
        val cacheBuilder = CacheBuilder<Any, Any>().apply {
            maximumSize { 10 }
        }

        assertEquals(10, cacheBuilder.maximumSize)
    }

    @Test
    fun maximumCacheSize_negativeValue() {
        val exception = assertFailsWith<IllegalArgumentException> {
            cacheBuilder<Any, Any> {
                maximumSize { -1 }
            }
        }
        assertEquals("maximum size must not be negative", exception.message)
    }

    @Test
    fun fakeTicker() {
        val ticker: Ticker = { 0L }
        val cacheBuilder = CacheBuilder<Any, Any>().apply {
            ticker { ticker }
        }
        assertEquals(ticker, cacheBuilder.ticker)
    }

    @Test
    fun buildWithMaxiumuSizeAndWeigher() {
        val exception = assertFailsWith<IllegalStateException> {
            cacheBuilder<Any, Any> {
                maximumSize { 10 }
                weigher(1) { _, _ -> 1 }
            }
        }
        assertEquals("maximum size can not be combined with weigher", exception.message)
    }

    @Test
    fun buildWithDefaults() {
        val cache = CacheBuilder<Any, Any>()
        assertEquals(Duration.INFINITE, cache.expireAfterWrite)
        assertEquals(Duration.INFINITE, cache.expireAfterAccess)
        assertEquals(-1L, cache.maximumSize)
        assertNull(cache.ticker)
    }
}
