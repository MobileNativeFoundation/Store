package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

abstract class StoreRevalidationConformance : SourceOfTruthSubstitutionTest() {
    @Test
    fun conditionalRefetch_notModified_emitsOwnerRevalidatedAndClearsStaleness() = runTest {
        var calls = 0
        val notModifiedGate = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcherOfResult {
                when (++calls) {
                    1 -> FetcherResult.Success("v1", etag = "e1")
                    2 -> {
                        notModifiedGate.await()
                        FetcherResult.NotModified("e1")
                    }
                    // A cold-baseline 304 commits ObsoleteRevalidation and legally self-heals
                    // with exactly one replanned conditional fetch.
                    3 -> FetcherResult.NotModified("e1")
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            store.stream(TestKey("1")).test {
                assertIs<StoreResult.Loading>(awaitItem())
                assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                store.invalidate(TestKey("1"))
                notModifiedGate.complete(Unit)

                while (true) {
                    when (val item = awaitItem()) {
                        is StoreResult.Data -> {
                            if (item.origin == Origin.FETCHER && !item.isStale) {
                                fail("legacy fresh FETCHER Data must be replaced by Revalidated")
                            }
                        }
                        is StoreResult.Revalidated -> {
                            assertTrue(item.age >= Duration.ZERO)
                            break
                        }
                        else -> fail("unexpected lifecycle item ${item::class.simpleName}")
                    }
                }
                cancelAndIgnoreRemainingEvents()
            }

            val callsAfterRevalidated = calls
            assertTrue(
                callsAfterRevalidated in 2..3,
                "the 304 cycle may self-heal one obsolete cold-baseline launch",
            )
            assertEquals("v1", store.get(TestKey("1")))
            assertEquals(
                callsAfterRevalidated,
                calls,
                "successful 304 must clear staleness before later planning",
            )
        } finally {
            notModifiedGate.complete(Unit)
            store.close()
        }
    }

    @Test
    fun slowCollector_neverLosesRevalidatedWhileConsecutiveDataConflates() = runTest {
        var calls = 0
        val secondFetchEntered = CompletableDeferred<Unit>()
        val releaseNotModified = CompletableDeferred<Unit>()
        val firstDataSeen = CompletableDeferred<Unit>()
        val releaseSlowCollector = CompletableDeferred<Unit>()
        val slowSawRevalidated = CompletableDeferred<Unit>()
        val received = mutableListOf<StoreResult<String>>()
        val key = TestKey("1")
        val store = testStore<TestKey, String> {
            fetcherOfResult {
                when (++calls) {
                    1 -> FetcherResult.Success("v1", etag = "e1")
                    2 -> {
                        secondFetchEntered.complete(Unit)
                        releaseNotModified.await()
                        FetcherResult.NotModified("e1")
                    }
                    // A cold-baseline 304 commits ObsoleteRevalidation and legally self-heals
                    // with exactly one replanned conditional fetch.
                    3 -> FetcherResult.NotModified("e1")
                    else -> error("unexpected fetch call $calls")
                }
            }
        }
        val slowCollector = backgroundScope.launch {
            store.stream(key).collect { result ->
                received += result
                when {
                    result is StoreResult.Data && !firstDataSeen.isCompleted -> {
                        firstDataSeen.complete(Unit)
                        releaseSlowCollector.await()
                    }
                    result is StoreResult.Revalidated -> slowSawRevalidated.complete(Unit)
                }
            }
        }

        try {
            firstDataSeen.awaitFromDefaultContext()
            store.invalidate(key)
            secondFetchEntered.awaitFromDefaultContext()

            store.stream(key).test {
                val joiningData = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v1", joiningData.value)
                assertTrue(joiningData.isStale)
                assertTrue(joiningData.refreshing)
                releaseNotModified.complete(Unit)

                while (true) {
                    when (val item = awaitItem()) {
                        is StoreResult.Data -> {
                            if (item.origin == Origin.FETCHER && !item.isStale) {
                                fail("legacy fresh FETCHER Data must be replaced by Revalidated")
                            }
                        }
                        is StoreResult.Revalidated -> break
                        else -> fail("unexpected lifecycle item ${item::class.simpleName}")
                    }
                }
                cancelAndIgnoreRemainingEvents()
            }

            releaseSlowCollector.complete(Unit)
            slowSawRevalidated.awaitFromDefaultContext()
            val afterInitialData = received.dropWhile { it !is StoreResult.Data }.drop(1)
            val beforeRevalidated = afterInitialData.takeWhile { it !is StoreResult.Revalidated }
            assertTrue(afterInitialData.any { it is StoreResult.Revalidated })
            assertTrue(
                beforeRevalidated.count { it is StoreResult.Data<*> } <= 1,
                "a blocked collector may retain only the latest consecutive Data before Revalidated",
            )
            assertTrue(calls in 2..3, "the 304 cycle may self-heal one obsolete cold-baseline launch")
        } finally {
            releaseNotModified.complete(Unit)
            releaseSlowCollector.complete(Unit)
            slowCollector.cancel()
            store.close()
            slowCollector.join()
        }
    }

    @Test
    fun slowCollector_neverLosesPostClearLoadingBeforeRefetchCompletes() = runTest {
        var calls = 0
        val firstDataSeen = CompletableDeferred<Unit>()
        val releaseSlowCollector = CompletableDeferred<Unit>()
        val refetchEntered = CompletableDeferred<Unit>()
        val releaseRefetch = CompletableDeferred<Unit>()
        val slowSawLoading = CompletableDeferred<Unit>()
        val slowSawFreshData = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcher {
                val call = ++calls
                if (call == 2) {
                    refetchEntered.complete(Unit)
                    releaseRefetch.await()
                }
                "v$call"
            }
        }
        val slowCollector = backgroundScope.launch {
            store.stream(TestKey("1")).collect { result ->
                when {
                    result is StoreResult.Data && !firstDataSeen.isCompleted -> {
                        firstDataSeen.complete(Unit)
                        releaseSlowCollector.await()
                    }
                    result is StoreResult.Loading && firstDataSeen.isCompleted ->
                        slowSawLoading.complete(Unit)
                    result is StoreResult.Data && result.value == "v2" ->
                        slowSawFreshData.complete(Unit)
                }
            }
        }

        try {
            firstDataSeen.awaitFromDefaultContext()
            store.clear(TestKey("1"))
            refetchEntered.awaitFromDefaultContext()
            releaseSlowCollector.complete(Unit)
            slowSawLoading.awaitFromDefaultContext()
            assertTrue(!releaseRefetch.isCompleted, "Loading must survive before refetch is released")
            releaseRefetch.complete(Unit)
            slowSawFreshData.awaitFromDefaultContext()
            assertEquals(2, calls)
        } finally {
            releaseSlowCollector.complete(Unit)
            releaseRefetch.complete(Unit)
            slowCollector.cancel()
            store.close()
            slowCollector.join()
        }
    }
}

class StoreRevalidationConformanceTest : StoreRevalidationConformance()

// Turbine's 3s default would nest inside the 25s shadow. Raising the Turbine deadline above
// the shadow makes runTest the only effective timeout.
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }

// Preserve Default-dispatch ordering and let the suite-level runTest bound own cancellation.
private suspend fun <T> CompletableDeferred<T>.awaitFromDefaultContext(): T =
    withContext(Dispatchers.Default) {
        await()
    }
