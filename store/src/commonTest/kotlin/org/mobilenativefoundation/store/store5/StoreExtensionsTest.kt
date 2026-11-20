package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store.store5.impl.extensions.freshOrNull
import org.mobilenativefoundation.store.store5.impl.extensions.freshOrResult
import org.mobilenativefoundation.store.store5.impl.extensions.getOrNull
import org.mobilenativefoundation.store.store5.impl.extensions.getOrResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreExtensionsTest {

    @Test
    fun getOrResult_returnsSuccessWhenDataAvailable() = runTest {
        val store = StoreBuilder.from(
            Fetcher.of<String, String> { "fetched_$it" },
        ).build()

        val result = store.getOrResult("key1")
        assertTrue(result.isSuccess)
        assertEquals("fetched_key1", result.getOrNull())
    }

    @Test
    fun getOrResult_returnsFailureWhenFetcherThrows() = runTest {
        val store = StoreBuilder.from(
            Fetcher.of<String, String> { throw IllegalStateException("Network error") },
        ).build()

        val result = store.getOrResult("key1")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("Network error", result.exceptionOrNull()?.message)
    }

    @Test
    fun getOrNull_returnsNullWhenFetcherThrows() = runTest {
        val store = StoreBuilder.from(
            Fetcher.of<String, String> { throw IllegalStateException("Network error") },
        ).build()

        val value = store.getOrNull("key1")
        assertNull(value)
    }

    @Test
    fun freshOrResult_returnsSuccessWhenDataAvailable() = runTest {
        val store = StoreBuilder.from(
            Fetcher.of<String, String> { "fresh_$it" },
        ).build()

        val result = store.freshOrResult("key1")
        assertTrue(result.isSuccess)
        assertEquals("fresh_key1", result.getOrNull())
    }

    @Test
    fun freshOrResult_returnsFailureWhenFetcherThrows() = runTest {
        val store = StoreBuilder.from(
            Fetcher.of<String, String> { throw RuntimeException("Failed to fetch fresh data") },
        ).build()

        val result = store.freshOrResult("key1")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is RuntimeException)
    }

    @Test
    fun freshOrNull_returnsNullWhenFetcherThrows() = runTest {
        val store = StoreBuilder.from(
            Fetcher.of<String, String> { throw RuntimeException("Failed to fetch fresh data") },
        ).build()

        val value = store.freshOrNull("key1")
        assertNull(value)
    }

    @Test
    fun getOrResult_rethrowsCancellationException() = runTest {
        val store = StoreBuilder.from(
            Fetcher.ofFlow<String, String> { key ->
                flow {
                    throw CancellationException("Cancelled")
                }
            },
        ).build()

        assertFailsWith<CancellationException> {
            store.getOrResult("key1")
        }
    }
}
