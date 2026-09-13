package org.mobilenativefoundation.store6.swift

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SwiftInteropTest {

    @Test
    fun swiftStoreKey_exposesNamespaceAndCanonicalId() {
        val key = SwiftStoreKey(namespace = "users", id = "42")
        assertEquals("users", key.namespace.value)
        assertEquals("42", key.canonicalId())
        assertEquals(SwiftStoreKey("users", "42"), key)
        assertEquals(SwiftStoreKey("users", "42").hashCode(), key.hashCode())
    }

    @Test
    fun swiftStore_completionValue_resolvesGet() = runTest {
        val store = swiftStore { key, completion ->
            completion("value-for-${key.canonicalId()}", null)
        }
        val value = store.get(SwiftStoreKey("users", "1"), Freshness.CachedOrFetch)
        assertEquals("value-for-1", value)
    }

    @Test
    fun storeStates_emitsLoadingThenData_withMillisAge() = runTest {
        val store = swiftStore { _, completion -> completion("v", null) }
        val key = SwiftStoreKey("users", "2")
        val states = storeStates(store, key, Freshness.CachedOrFetch)
        val collected = mutableListOf<StoreStateBridge>()
        states.first { state ->
            collected += state
            state.kind == StoreStateKind.DATA
        }
        assertTrue(collected.first().kind == StoreStateKind.LOADING || collected.first().kind == StoreStateKind.DATA)
        val data = collected.last()
        assertEquals(StoreStateKind.DATA, data.kind)
        assertEquals("v", data.value)
        assertTrue(data.ageMillis >= 0L)
    }

    @Test
    fun swiftStore_completionError_surfacesAsErrorState() = runTest {
        val store = swiftStore { _, completion -> completion(null, "backend unavailable") }
        val key = SwiftStoreKey("users", "3")
        val error = storeStates(store, key, Freshness.CachedOrFetch)
            .first { it.kind == StoreStateKind.ERROR }
        assertTrue(error.error != null)
    }

    @Test
    fun storeGet_throwsStoreExceptionForFailedFetch() = runTest {
        val store = swiftStore { _, completion -> completion(null, "backend unavailable") }
        assertFailsWith<StoreException> {
            storeGet(store, SwiftStoreKey("users", "4"), Freshness.MustBeFresh)
        }
    }

    @Test
    fun maxAgeFreshness_isMaxAgeWithGivenDuration() {
        val freshness = maxAgeFreshness(notOlderThanMillis = 60_000)
        assertTrue(freshness is Freshness.MaxAge)
        assertEquals(60_000, (freshness as Freshness.MaxAge).notOlderThan.inWholeMilliseconds)
    }
}
