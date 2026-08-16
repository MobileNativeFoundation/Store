package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalStoreApi::class)
class FetcherContractTest {
    @Test
    fun fetcherResultError_cancellationIsEquivalentToThrowingCancellation() = runTest {
        val returnedCancellation = CancellationException("fetch cancelled")
        val richStore = store<TestKey, String> {
            fetcherOfResult { FetcherResult.Error(returnedCancellation) }
        }
        val thrownCancellation = CancellationException("fetch cancelled")
        val plainStore = store<TestKey, String> {
            fetcher { throw thrownCancellation }
        }

        try {
            val returnedFailure =
                assertFailsWith<CancellationException> {
                    richStore.get(TestKey("1"))
                }
            assertEquals("fetch cancelled", returnedFailure.message)
            assertTrue(
                returnedFailure === returnedCancellation ||
                    returnedFailure.cause === returnedCancellation,
            )

            val thrownFailure =
                assertFailsWith<CancellationException> {
                    plainStore.get(TestKey("1"))
                }
            assertEquals("fetch cancelled", thrownFailure.message)
            assertTrue(
                thrownFailure === thrownCancellation || thrownFailure.cause === thrownCancellation,
            )
        } finally {
            richStore.close()
            plainStore.close()
        }
    }

    @Test
    fun richSuccess_streamEmitsLoadingThenFetcherData() = runTest {
        val store = store<TestKey, String> {
            fetcherOfResult { FetcherResult.Success("v", etag = "e1") }
        }

        try {
            store.stream(TestKey("1")).test {
                assertIs<StoreResult.Loading>(awaitItem())
                val data = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v", data.value)
                assertEquals(Origin.FETCHER, data.origin)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun richError_matchesThrownFetcherFailureForGetAndStream() = runTest {
        val boom = IllegalStateException("boom")
        val store = store<TestKey, String> {
            fetcherOfResult { FetcherResult.Error(boom) }
        }

        try {
            val getFailure = assertFailsWith<StoreException> { store.get(TestKey("1")) }
            val getFetch = assertIs<StoreError.Fetch>(getFailure.error)
            assertTrue(getFetch.cause === boom)

            store.stream(TestKey("1")).test {
                assertIs<StoreResult.Loading>(awaitItem())
                val failure = assertIs<StoreResult.Error>(awaitItem())
                val streamFetch = assertIs<StoreError.Fetch>(failure.error)
                assertTrue(streamFetch.cause === boom)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun deletedAfterResident_emitsOneLoadingAndOneMissingWithoutLoop() = runTest {
        var calls = 0
        val store = store<TestKey, String> {
            fetcherOfResult {
                when (++calls) {
                    1 -> FetcherResult.Success("v1")
                    2, 3 -> FetcherResult.Deleted
                    else -> error("unexpected fetch call $calls")
                }
            }
        }
        val key = TestKey("1")

        try {
            assertEquals("v1", store.get(key))

            store.stream(key).test {
                assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                store.invalidate(key)

                val events = listOf(awaitItem(), awaitItem())
                assertEquals(1, events.count { it is StoreResult.Loading })
                val failures = events.filterIsInstance<StoreResult.Error>()
                assertEquals(1, failures.size)
                val missing = assertIs<StoreError.Missing>(failures.single().error)
                assertTrue(missing.message.lowercase().contains("deleted"))
                expectNoEvents()

                val laterFailure = assertFailsWith<StoreException> { store.get(key) }
                val laterMissing = assertIs<StoreError.Missing>(laterFailure.error)
                assertTrue(laterMissing.message.lowercase().contains("deleted"))
                assertEquals(3, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun deletedOnFirstGet_reportsMissing() = runTest {
        val store = store<TestKey, String> {
            fetcherOfResult { FetcherResult.Deleted }
        }

        try {
            val failure = assertFailsWith<StoreException> { store.get(TestKey("1")) }
            val missing = assertIs<StoreError.Missing>(failure.error)
            assertTrue(missing.message.lowercase().contains("deleted"))
        } finally {
            store.close()
        }
    }

    @Test
    fun deletedOnFirstStream_emitsLoadingThenMissingAndStaysLive() = runTest {
        val store = store<TestKey, String> {
            fetcherOfResult { FetcherResult.Deleted }
        }

        try {
            store.stream(TestKey("1")).test {
                assertIs<StoreResult.Loading>(awaitItem())
                val failure = assertIs<StoreResult.Error>(awaitItem())
                val missing = assertIs<StoreError.Missing>(failure.error)
                assertTrue(missing.message.lowercase().contains("deleted"))
                assertEquals(false, failure.servedStale)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun notModifiedWithoutResident_reportsMissingForGetAndStream() = runTest {
        val store = store<TestKey, String> {
            fetcherOfResult { FetcherResult.NotModified(etag = "e1") }
        }

        try {
            val getFailure = assertFailsWith<StoreException> { store.get(TestKey("1")) }
            val getMissing = assertIs<StoreError.Missing>(getFailure.error)
            assertTrue(getMissing.message.contains("NotModified"))

            store.stream(TestKey("1")).test {
                assertIs<StoreResult.Loading>(awaitItem())
                val failure = assertIs<StoreResult.Error>(awaitItem())
                val streamMissing = assertIs<StoreError.Missing>(failure.error)
                assertTrue(streamMissing.message.contains("NotModified"))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun successNotModifiedAndDeleted_updateAndForgetBookkeeper() = runTest {
        var calls = 0
        val bookkeeper = InMemoryBookkeeper()
        val clock = FakeWallClock(now = 1_000L)
        val store = storeWith<TestKey, String>(clock = clock, bookkeeper = bookkeeper) {
            fetcherOfResult {
                when (++calls) {
                    1 -> FetcherResult.Success("v1", etag = "e1")
                    2 -> FetcherResult.NotModified(etag = "e2")
                    3 -> FetcherResult.Deleted
                    else -> error("unexpected fetch call $calls")
                }
            }
        }
        val key = TestKey("1")

        try {
            assertEquals("v1", store.get(key))
            val seeded = assertNotNull(bookkeeper.status(key))
            assertEquals("e1", seeded.meta?.etag)
            assertEquals(1L, seeded.lastSuccessSequence)

            clock.now = 2_000L
            assertEquals("v1", store.get(key, Freshness.MustBeFresh))
            val revalidated = assertNotNull(bookkeeper.status(key))
            assertEquals("e2", revalidated.meta?.etag)
            assertEquals(2L, revalidated.lastSuccessSequence)
            assertTrue(
                assertNotNull(revalidated.lastSuccessSequence) >
                    assertNotNull(seeded.lastSuccessSequence),
            )

            val deleted =
                assertFailsWith<StoreException> {
                    store.get(key, Freshness.MustBeFresh)
                }
            val missing = assertIs<StoreError.Missing>(deleted.error)
            assertTrue(missing.message.lowercase().contains("deleted"))
            assertNull(bookkeeper.status(key))
            assertEquals(3, calls)
        } finally {
            store.close()
        }
    }

    @Test
    fun notModifiedWithNullEtag_retainsPreviousEtag() = runTest {
        var calls = 0
        val bookkeeper = InMemoryBookkeeper()
        val store = storeWith<TestKey, String>(bookkeeper = bookkeeper) {
            fetcherOfResult {
                when (++calls) {
                    1 -> FetcherResult.Success("v1", etag = "e1")
                    2 -> FetcherResult.NotModified(etag = null)
                    else -> error("unexpected fetch call $calls")
                }
            }
        }
        val key = TestKey("1")

        try {
            assertEquals("v1", store.get(key))
            assertEquals("v1", store.get(key, Freshness.MustBeFresh))
            assertEquals("e1", bookkeeper.status(key)?.meta?.etag)
        } finally {
            store.close()
        }
    }
}
