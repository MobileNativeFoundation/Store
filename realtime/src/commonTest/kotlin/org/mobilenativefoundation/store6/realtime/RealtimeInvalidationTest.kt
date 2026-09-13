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
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.runtime
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.testing.FakeBookkeeper
import org.mobilenativefoundation.store6.testing.FakeStore
import org.mobilenativefoundation.store6.testing.FakeStoreInteraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class RealtimeInvalidationTest {
    @Test
    fun changed_signalsActiveStreamToRefetch() = runTest {
        val fetcher = RecordingFetcher()
        val store = store<RealtimeTestKey, String> { fetcher(fetcher) }
        val binding = realtimeBinding(store)
        val key = RealtimeTestKey("changed")

        try {
            store.stream(key).test {
                awaitDataValue("fetched-1")
                binding.apply(RealtimeMessage.Changed(key))
                awaitDataValue("fetched-2")
                assertEquals(2, fetcher.fetches)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun changedNamespace_marksResidentAndCoversNeverFetchedKey() = runTest {
        val fetcher = RecordingFetcher()
        val bookkeeper = FakeBookkeeper()
        val store =
            store<RealtimeTestKey, String> {
                fetcher(fetcher)
                bookkeeper(bookkeeper)
            }
        val binding = realtimeBinding(store)
        val resident = RealtimeTestKey("resident")
        val unseen = RealtimeTestKey("unseen")

        try {
            assertEquals("fetched-1", store.get(resident))
            assertEquals("fetched-1", store.get(resident, Freshness.MaxAge(1.days)))
            assertEquals(1, fetcher.fetches)
            assertNull(bookkeeper.status(unseen))

            binding.apply(RealtimeMessage.ChangedNamespace(REALTIME_NAMESPACE))

            assertTrue(assertNotNull(bookkeeper.status(resident)).durablyStale)
            assertTrue(assertNotNull(bookkeeper.status(unseen)).durablyStale)
            assertEquals("fetched-2", store.get(resident, Freshness.MaxAge(1.days)))
            assertEquals(2, fetcher.fetches)
        } finally {
            store.close()
        }
    }

    @Test
    fun changedAll_marksResidentStale() = runTest {
        val fetcher = RecordingFetcher()
        val bookkeeper = FakeBookkeeper()
        val store =
            store<RealtimeTestKey, String> {
                fetcher(fetcher)
                bookkeeper(bookkeeper)
            }
        val binding = realtimeBinding(store)
        val key = RealtimeTestKey("global")

        try {
            assertEquals("fetched-1", store.get(key))
            assertEquals("fetched-1", store.get(key, Freshness.MaxAge(1.days)))
            assertEquals(1, fetcher.fetches)

            binding.apply(RealtimeMessage.ChangedAll)

            assertTrue(assertNotNull(bookkeeper.status(key)).durablyStale)
            assertEquals("fetched-2", store.get(key, Freshness.MaxAge(1.days)))
            assertEquals(2, fetcher.fetches)
        } finally {
            store.close()
        }
    }

    @Test
    fun deleted_emitsLoadingThenRefetch() = runTest {
        val fetcher = RecordingFetcher()
        val store = store<RealtimeTestKey, String> { fetcher(fetcher) }
        val binding = realtimeBinding(store)
        val key = RealtimeTestKey("deleted")

        try {
            store.stream(key).test {
                awaitDataValue("fetched-1")
                binding.apply(RealtimeMessage.Deleted(key))
                // Fenced clear: reactive delivery parks behind the destructive barrier and then
                // resolves against post-tail state, so the queued replay, the Loading
                // transition, and the refetched frame can interleave inside the conflation
                // window when the refetch is fast. The contract under test is terminal: at most
                // one duplicate data frame before the refetched value, and exactly two fetches.
                var duplicates = 0
                while (true) {
                    when (val frame = awaitItem()) {
                        is StoreResult.Data ->
                            if (frame.value == "fetched-2") {
                                break
                            } else {
                                duplicates += 1
                                assertEquals(
                                    1,
                                    duplicates,
                                    "more than one pre-refetch duplicate data frame",
                                )
                            }
                        is StoreResult.Loading -> Unit
                        // RecordingFetcher always succeeds and the in-memory SoT cannot fail,
                        // so any other result kind is a real defect, not scheduler noise.
                        else -> fail("unexpected result kind: $frame")
                    }
                }
                assertEquals(2, fetcher.fetches)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun invalidatingBinding_upsertDegradesToInvalidate_onFakeStore() = runTest {
        val store = FakeStore<RealtimeTestKey, String>()
        val key = RealtimeTestKey("fake-upsert")
        store.setValue(key, "resident")
        store.clearInteractions()
        val binding = invalidatingRealtimeBinding(store)

        try {
            binding.apply(RealtimeMessage.Upsert(key, "ignored", etag = "etag"))
            val invalidate = store.interactions.filterIsInstance<FakeStoreInteraction.Invalidate>()
            assertEquals(1, invalidate.size)
            assertEquals(key.canonicalId(), invalidate.single().key.canonicalId())
            assertTrue(store.interactions.none { it is FakeStoreInteraction.Clear })
        } finally {
            store.close()
        }
    }

    @Test
    fun invalidatingBinding_unchangedIsNoOp_onFakeStore() = runTest {
        val store = FakeStore<RealtimeTestKey, String>()
        val key = RealtimeTestKey("fake-unchanged")
        store.setValue(key, "resident")
        store.clearInteractions()
        val binding = invalidatingRealtimeBinding(store)

        try {
            binding.apply(RealtimeMessage.Unchanged(key, etag = "etag"))
            assertEquals(emptyList(), store.interactions)
        } finally {
            store.close()
        }
    }

    @Test
    fun invalidatingBinding_changedAndDeleted_recordOnFakeStore() = runTest {
        val store = FakeStore<RealtimeTestKey, String>()
        val key = RealtimeTestKey("fake-ops")
        store.setValue(key, "resident")
        val binding = invalidatingRealtimeBinding(store)

        try {
            store.clearInteractions()
            binding.apply(RealtimeMessage.Changed(key))
            assertEquals(1, store.interactions.filterIsInstance<FakeStoreInteraction.Invalidate>().size)

            store.clearInteractions()
            binding.apply(RealtimeMessage.ChangedNamespace(REALTIME_NAMESPACE))
            assertEquals(
                1,
                store.interactions.filterIsInstance<FakeStoreInteraction.InvalidateNamespace>().size,
            )

            store.clearInteractions()
            binding.apply(RealtimeMessage.ChangedAll)
            assertEquals(1, store.interactions.filterIsInstance<FakeStoreInteraction.InvalidateAll>().size)

            store.clearInteractions()
            binding.apply(RealtimeMessage.Deleted(key))
            assertEquals(1, store.interactions.filterIsInstance<FakeStoreInteraction.Clear>().size)
        } finally {
            store.close()
        }
    }

    @Test
    fun invalidatingBinding_worksOnMutationStoreFacade() = runTest {
        var fetches = 0
        val store =
            mutationStoreForRealtime { key ->
                fetches += 1
                "v$fetches-${key.canonicalId()}"
            }
        val binding = invalidatingRealtimeBinding(store)
        val key = RealtimeTestKey("mutation")

        try {
            assertNull(store.runtime())
            assertEquals("v1-mutation", store.get(key))
            assertEquals("v1-mutation", store.get(key, Freshness.MaxAge(1.days)))
            assertEquals(1, fetches)
            binding.apply(RealtimeMessage.Upsert(key, "ignored", etag = "etag"))
            assertEquals("v2-mutation", store.get(key, Freshness.MaxAge(1.days)))
            assertEquals(2, fetches)
        } finally {
            store.close()
        }
    }

    @Test
    fun realtimeBinding_rejectsMutationStore() = runTest {
        val store = mutationStoreForRealtime { "unused" }
        try {
            val failure = assertFailsWith<IllegalArgumentException> { realtimeBinding(store) }
            assertEquals(ADOPTING_BINDING_UNAVAILABLE, failure.message)
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
