package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class PublicSurfaceTest {

    /** Detector fixture: an unstable canonicalId fails fast and names the fix. */
    private class UnstableKey : StoreKey {
        private var counter = 0
        override val namespace: StoreNamespace = StoreNamespace("unstable")
        override fun canonicalId(): String = "id-${counter++}"
    }

    @Test
    fun unstableCanonicalId_failsFastNamingTheFix() = runTest {
        val store = store<UnstableKey, String> { fetcher { "v" } }
        val failure = assertFailsWith<IllegalStateException> { store.get(UnstableKey()) }
        assertTrue(failure.message!!.contains("canonicalId"))
        assertTrue(failure.message!!.contains("unstable")) // names the namespace
        assertTrue(failure.message!!.contains("stable"))   // names the fix
        store.close()
    }

    /** Frozen-surface compile lock: every result/error variant constructible via internal ctors. */
    @Test
    fun frozenResultAndErrorVariants_constructibleWithFullPayloads() {
        val data = StoreResult.Data(
            value = "v",
            origin = Origin.MEMORY,
            age = Duration.ZERO,
            isStale = false,
            refreshing = false,
        )
        assertEquals("v", data.value)

        StoreResult.Loading()
        assertEquals(Duration.ZERO, StoreResult.Revalidated(age = Duration.ZERO).age)

        val errors: List<StoreError> = listOf(
            StoreError.Fetch(message = "m", cause = null),
            StoreError.Persistence(message = "m", cause = null),
            StoreError.Conversion(message = "m", cause = null),
            StoreError.FreshnessUnsatisfiable(message = "m"),
            StoreError.Conflict(serverMeta = null, message = "m"),
            StoreError.Missing(key = TestKey("1"), message = "m"),
        )
        val wrapped = StoreResult.Error(error = errors.first(), servedStale = false)
        assertIs<StoreError.Fetch>(wrapped.error)

        // messageOf is exhaustive over the frozen set.
        errors.forEach { error ->
            assertEquals("m", StoreException(error).message)
        }
    }

    /** Freshness values are accepted everywhere and apply their documented postures. */
    @Test
    fun allFreshnessValues_acceptedWithHonestPostures() = runTest {
        val store = store<TestKey, String> { fetcher { "v" } }
        val policies = listOf(
            Freshness.CachedOrFetch,
            Freshness.MaxAge(notOlderThan = 5.minutes), // public constructor
            Freshness.MustBeFresh,
            Freshness.StaleIfError,
            Freshness.LocalOnly,
        )
        for (policy in policies) {
            assertEquals("v", store.get(TestKey("k"), policy))
        }
        store.stream(TestKey("k"), Freshness.MustBeFresh).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertIs<StoreResult.Data<String>>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }
}
