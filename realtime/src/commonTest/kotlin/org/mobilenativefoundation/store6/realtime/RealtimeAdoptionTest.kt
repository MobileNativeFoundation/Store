@file:OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)

package org.mobilenativefoundation.store6.realtime

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.testing.FakeBookkeeper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RealtimeAdoptionTest {
    @Test
    fun upsert_emitsSotData_withoutFetch() = runTest {
        val fetcher = RecordingFetcher()
        val store = store<RealtimeTestKey, String> { fetcher(fetcher) }
        val binding = realtimeBinding(store)
        val key = RealtimeTestKey("adopt")

        try {
            store.stream(key).test {
                val fetched = awaitDataValue("fetched-1")
                assertEquals(Origin.FETCHER, fetched.origin)
                assertEquals(1, fetcher.fetches)

                binding.apply(RealtimeMessage.Upsert(key, "pushed", etag = "push-etag"))

                val adopted = awaitDataValue("pushed")
                assertEquals(Origin.SOT, adopted.origin)
                assertEquals(1, fetcher.fetches)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun upsert_recordsBookkeepingSuccess_laterCachedOrFetchSkipsFetch() = runTest {
        val fetcher = RecordingFetcher()
        val bookkeeper = FakeBookkeeper()
        val store =
            store<RealtimeTestKey, String> {
                fetcher(fetcher)
                bookkeeper(bookkeeper)
            }
        val binding = realtimeBinding(store)
        val key = RealtimeTestKey("fresh")

        try {
            assertEquals("fetched-1", store.get(key))
            assertEquals(1, fetcher.fetches)
            store.invalidate(key)
            assertTrue(assertNotNull(bookkeeper.status(key)).durablyStale)

            binding.apply(RealtimeMessage.Upsert(key, "pushed", etag = "push-etag"))

            assertFalse(assertNotNull(bookkeeper.status(key)).durablyStale)
            assertEquals("pushed", store.get(key, Freshness.CachedOrFetch))
            assertEquals(1, fetcher.fetches)
        } finally {
            store.close()
        }
    }

    @Test
    fun upsert_etag_flowsIntoNextConditionalFetch() = runTest {
        val fetcher = RecordingFetcher()
        val store = store<RealtimeTestKey, String> { fetcher(fetcher) }
        val binding = realtimeBinding(store)
        val key = RealtimeTestKey("etag")

        try {
            assertEquals("fetched-1", store.get(key))
            assertEquals(listOf<String?>(null), fetcher.etags)

            binding.apply(RealtimeMessage.Upsert(key, "pushed", etag = "push-etag"))
            store.invalidate(key)
            assertEquals("fetched-2", store.get(key, Freshness.MustBeFresh))

            assertEquals(listOf<String?>(null, "push-etag"), fetcher.etags)
            assertEquals(2, fetcher.fetches)
        } finally {
            store.close()
        }
    }

    @Test
    fun upsert_applyThenConfirmFresh_ordering() = runTest {
        val store = store<RealtimeTestKey, String> { fetcher { "fetched" } }
        val handle = RecordingWriteHandle()
        val binding = RealtimeBinding(store, handle)
        val key = RealtimeTestKey("order")

        try {
            binding.apply(RealtimeMessage.Upsert(key, "pushed", etag = "ack-etag"))
            assertEquals(listOf("apply", "confirmFresh"), handle.events)
        } finally {
            store.close()
        }
    }

    @Test
    fun unchanged_clearsDurableStalenessWithoutFetch() = runTest {
        val fetcher = RecordingFetcher()
        val bookkeeper = FakeBookkeeper()
        val store =
            store<RealtimeTestKey, String> {
                fetcher(fetcher)
                bookkeeper(bookkeeper)
            }
        val binding = realtimeBinding(store)
        val key = RealtimeTestKey("304")

        try {
            assertEquals("fetched-1", store.get(key))
            store.invalidate(key)
            assertTrue(assertNotNull(bookkeeper.status(key)).durablyStale)

            binding.apply(RealtimeMessage.Unchanged(key, etag = "still-fresh"))

            assertFalse(assertNotNull(bookkeeper.status(key)).durablyStale)
            assertEquals("fetched-1", store.get(key, Freshness.CachedOrFetch))
            assertEquals(1, fetcher.fetches)
        } finally {
            store.close()
        }
    }

    @Test
    fun unchanged_withoutResidence_isNoOp() = runTest {
        val fetcher = RecordingFetcher()
        val store = store<RealtimeTestKey, String> { fetcher(fetcher) }
        val binding = realtimeBinding(store)
        val key = RealtimeTestKey("absent")

        try {
            binding.apply(RealtimeMessage.Unchanged(key, etag = "unused"))
            assertEquals(0, fetcher.fetches)
            assertEquals("fetched-1", store.get(key))
            assertEquals(1, fetcher.fetches)
        } finally {
            store.close()
        }
    }
}

private suspend fun app.cash.turbine.ReceiveTurbine<StoreResult<String>>.awaitDataValue(
    expected: String,
): StoreResult.Data<String> {
    while (true) {
        val item = awaitItem()
        if (item is StoreResult.Data<String> && item.value == expected) return item
    }
}

// Turbine's 3s default would nest inside the 25s shadow. Raising the Turbine deadline above
// the shadow makes runTest the only effective timeout.
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
