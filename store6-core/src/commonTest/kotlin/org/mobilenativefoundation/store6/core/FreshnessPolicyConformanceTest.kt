package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalStoreApi::class)
open class FreshnessPolicyConformanceTest : SourceOfTruthSubstitutionTest() {
    @Test
    fun maxAgeWithinBoundServesResidentWithoutSecondFetch() = runTest {
        val clock = FakeWallClock(now = 0L)
        var calls = 0
        val store = testStoreWith<TestKey, String>(clock = clock) {
            fetcher { "v${++calls}" }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            clock.now = 60.seconds.inWholeMilliseconds

            assertEquals(
                "v1",
                store.get(TestKey("1"), Freshness.MaxAge(notOlderThan = 5.minutes)),
            )
            assertEquals(1, calls)
        } finally {
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun maxAgeOverBoundGetBlocksForAndReturnsFreshValue() = runTest {
        val clock = FakeWallClock(now = 0L)
        var calls = 0
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val store = testStoreWith<TestKey, String>(clock = clock) {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondStarted.complete(Unit)
                        secondGate.await()
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            clock.now = 600.seconds.inWholeMilliseconds

            val fresh =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.get(TestKey("1"), Freshness.MaxAge(notOlderThan = 5.minutes))
                }
            assertFalse(fresh.isCompleted)
            secondStarted.awaitFromDefault()
            secondGate.complete(Unit)

            assertEquals("v2", fresh.await())
            assertEquals(2, calls)
        } finally {
            secondGate.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun maxAgeOverBoundStreamWithholdsResidentUntilFreshValue() = runTest {
        val clock = FakeWallClock(now = 0L)
        var calls = 0
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val store = testStoreWith<TestKey, String>(clock = clock) {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondStarted.complete(Unit)
                        secondGate.await()
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            val key = TestKey("1")
            turbineScope {
                // A retained LocalOnly observer makes the empty-reader boundary public and
                // byte-identical across every substituted SourceOfTruth.
                val localCollector =
                    store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                val missing = assertIs<StoreResult.Error>(localCollector.awaitItem())
                assertIs<StoreError.Missing>(missing.error)
                assertFalse(missing.servedStale)
                assertEquals(0, calls)
                awaitCurrentReaderFirstDelivery(key)

                val initialCollector = store.stream(key).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(initialCollector.awaitItem())
                val initial = assertIs<StoreResult.Data<String>>(initialCollector.awaitItem())
                assertEquals("v1", initial.value)
                assertFalse(initial.isStale, "seed frame must not be stale: $initial")
                assertFalse(
                    initial.refreshing,
                    "seed frame must not remain refreshing: $initial",
                )
                assertEquals(1, calls)
                val localInitial = assertIs<StoreResult.Data<String>>(localCollector.awaitItem())
                assertEquals("v1", localInitial.value)
                assertFalse(localInitial.isStale)
                assertFalse(localInitial.refreshing)
                clock.now = 600.seconds.inWholeMilliseconds

                val collector =
                    store.stream(
                        key,
                        Freshness.MaxAge(notOlderThan = 5.minutes),
                    ).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(collector.awaitItem())
                secondStarted.awaitFromDefault()
                secondGate.complete(Unit)

                val fresh = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("v2", fresh.value)
                assertEquals(Duration.ZERO, fresh.age)
                assertFalse(fresh.isStale, "fresh frame must not be stale: $fresh")
                assertFalse(
                    fresh.refreshing,
                    "fresh terminal must not remain refreshing: $fresh",
                )
                collector.expectNoEvents()
                localCollector.cancelAndIgnoreRemainingEvents()
                initialCollector.cancelAndIgnoreRemainingEvents()
                collector.cancelAndIgnoreRemainingEvents()
            }
            assertEquals(2, calls)
        } finally {
            secondGate.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun maxAgeOverBoundFailureDoesNotFallBackForGetOrStream() = runTest {
        val clock = FakeWallClock(now = 0L)
        val boom = IllegalStateException("boom")
        var calls = 0
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val thirdStarted = CompletableDeferred<Unit>()
        val thirdGate = CompletableDeferred<Unit>()
        val store = testStoreWith<TestKey, String>(clock = clock) {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondStarted.complete(Unit)
                        secondGate.await()
                        throw boom
                    }
                    3 -> {
                        thirdStarted.complete(Unit)
                        thirdGate.await()
                        throw boom
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            clock.now = 600.seconds.inWholeMilliseconds

            val get =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        store.get(TestKey("1"), Freshness.MaxAge(notOlderThan = 5.minutes))
                    }
                }
            assertFalse(get.isCompleted)
            secondStarted.awaitFromDefault()
            secondGate.complete(Unit)

            val getFailure = assertIs<StoreException>(get.await().exceptionOrNull())
            val getFetch = assertIs<StoreError.Fetch>(getFailure.error)
            assertTrue(getFetch.cause === boom)

            store.stream(
                TestKey("1"),
                Freshness.MaxAge(notOlderThan = 5.minutes),
            ).test {
                assertIs<StoreResult.Loading>(awaitItem())
                thirdStarted.awaitFromDefault()
                thirdGate.complete(Unit)

                val failure = assertIs<StoreResult.Error>(awaitItem())
                val streamFetch = assertIs<StoreError.Fetch>(failure.error)
                assertTrue(streamFetch.cause === boom)
                assertFalse(failure.servedStale)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(3, calls)
        } finally {
            secondGate.complete(Unit)
            thirdGate.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun mustBeFreshRefetchesFreshResident() = runTest {
        var calls = 0
        val store = testStore<TestKey, String> {
            fetcher { "v${++calls}" }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            assertEquals("v2", store.get(TestKey("1"), Freshness.MustBeFresh))
            assertEquals(2, calls)
        } finally {
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun mustBeFreshFailureHasNoFallbackAndStreamCompletes() = runTest {
        val boom = IllegalStateException("boom")
        var calls = 0
        val store = testStore<TestKey, String> {
            fetcher {
                if (++calls == 1) "v1" else throw boom
            }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))

            val getFailure =
                assertFailsWith<StoreException> {
                    store.get(TestKey("1"), Freshness.MustBeFresh)
                }
            val getFetch = assertIs<StoreError.Fetch>(getFailure.error)
            assertTrue(getFetch.cause === boom)

            store.stream(TestKey("1"), Freshness.MustBeFresh).test {
                assertIs<StoreResult.Loading>(awaitItem())
                val failure = assertIs<StoreResult.Error>(awaitItem())
                val streamFetch = assertIs<StoreError.Fetch>(failure.error)
                assertTrue(streamFetch.cause === boom)
                assertFalse(failure.servedStale)
                awaitComplete()
            }
        } finally {
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun staleIfErrorAfterInvalidationWaitsForFailureThenReturnsResident() = runTest {
        var calls = 0
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val boom = IllegalStateException("boom")
        val store = testStore<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondStarted.complete(Unit)
                        secondGate.await()
                        throw boom
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            store.invalidate(TestKey("1"))

            val fallback =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.get(TestKey("1"), Freshness.StaleIfError)
                }
            assertFalse(fallback.isCompleted)
            secondStarted.awaitFromDefault()
            secondGate.complete(Unit)

            assertEquals("v1", fallback.await())
            assertEquals(2, calls)
        } finally {
            secondGate.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun staleIfErrorWithoutResidentThrowsFetch() = runTest {
        val boom = IllegalStateException("boom")
        var calls = 0
        val store = testStore<TestKey, String> {
            fetcher {
                calls++
                throw boom
            }
        }

        try {
            val failure =
                assertFailsWith<StoreException> {
                    store.get(TestKey("1"), Freshness.StaleIfError)
                }
            val fetch = assertIs<StoreError.Fetch>(failure.error)
            assertTrue(fetch.cause === boom)
            assertEquals(1, calls)
        } finally {
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun staleIfErrorStreamEmitsStaleThenServedStaleErrorAndStaysLive() = runTest {
        var calls = 0
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val boom = IllegalStateException("boom")
        val store = testStore<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondStarted.complete(Unit)
                        secondGate.await()
                        throw boom
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(TestKey("1")))
            store.invalidate(TestKey("1"))

            store.stream(TestKey("1"), Freshness.StaleIfError).test {
                val stale = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v1", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)

                secondStarted.awaitFromDefault()
                secondGate.complete(Unit)

                var terminal = awaitItem()
                var queuedStaleReplays = 0
                while (terminal is StoreResult.Data<*>) {
                    queuedStaleReplays += 1
                    assertTrue(
                        queuedStaleReplays <= QUEUED_STALE_REPLAY_BOUND,
                        "queued stale replays exceeded the ratified bound",
                    )
                    assertEquals("v1", terminal.value)
                    assertTrue(terminal.isStale)
                    assertTrue(terminal.refreshing)
                    terminal = awaitItem()
                }
                val failure = assertIs<StoreResult.Error>(terminal)
                val fetch = assertIs<StoreError.Fetch>(failure.error)
                assertTrue(fetch.cause === boom)
                assertTrue(failure.servedStale)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(2, calls)
        } finally {
            secondGate.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun localOnlyWithoutResidentReportsMissingWithoutLoadingOrFetcherCall() = runTest {
        var calls = 0
        val store = testStore<TestKey, String> {
            fetcher {
                calls++
                "remote"
            }
        }

        try {
            val getFailure =
                assertFailsWith<StoreException> {
                    store.get(TestKey("1"), Freshness.LocalOnly)
                }
            val getMissing = assertIs<StoreError.Missing>(getFailure.error)
            assertTrue(getMissing.message.contains("LocalOnly"))

            store.stream(TestKey("1"), Freshness.LocalOnly).test {
                val failure = assertIs<StoreResult.Error>(awaitItem())
                val streamMissing = assertIs<StoreError.Missing>(failure.error)
                assertTrue(streamMissing.message.contains("LocalOnly"))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(0, calls)
        } finally {
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun localOnlyResidentIgnoresInvalidationForGetAndStream() = runTest {
        var calls = 0
        val store = testStore<TestKey, String> {
            fetcher { "v${++calls}" }
        }
        val key = TestKey("1")

        try {
            assertEquals("v1", store.get(key))

            store.stream(key, Freshness.LocalOnly).test {
                assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                store.invalidate(key)

                assertEquals("v1", store.get(key, Freshness.LocalOnly))
                expectNoEvents()
                assertEquals(1, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun dataAgeUsesInjectedWallClock() = runTest {
        val clock = FakeWallClock(now = 1_000L)
        val store = testStoreWith<TestKey, String>(clock = clock) {
            fetcher { "v" }
        }

        try {
            assertEquals("v", store.get(TestKey("1")))
            clock.now = 31_000L

            store.stream(TestKey("1")).test {
                val data = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals(30.seconds, data.age)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.closeAndSettleForTest()
        }
    }
}

private const val QUEUED_STALE_REPLAY_BOUND = 1

// 017 residual-deadline repair: Turbine's 3s default nested inside the 25s shadow; raise the
// Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15).
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }

// Preserve Default-dispatch ordering and let the suite-level runTest bound own cancellation.
private suspend fun <T> CompletableDeferred<T>.awaitFromDefault(): T =
    withContext(Dispatchers.Default) {
        await()
    }
