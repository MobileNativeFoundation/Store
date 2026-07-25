package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.mobilenativefoundation.store6.core.internal.RotatingSlotSourceOfTruth
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)
class ReaderGenerationRecoveryTest {
    private val key = TestKey("rotating-slot")

    @Test
    fun readerGen_clear_reconnectsRotatingSlotReader() = runTest {
        val sourceOfTruth = RotatingSlotSourceOfTruth<TestKey, String>()
        var fetchCalls = 0
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher {
                when (++fetchCalls) {
                    1 -> "v1"
                    2 -> {
                        sourceOfTruth.awaitCurrentSlotReaderDelivery(key)
                        "v2"
                    }
                    else -> error("unexpected fetch call $fetchCalls")
                }
            }
        }

        try {
            store.stream(key).test {
                while (true) {
                    val item = awaitItem()
                    if (item is StoreResult.Data && item.value == "v1") break
                }
                runCurrent()
                val subscriptionsBeforeClear = sourceOfTruth.subscriptionCount(key)

                store.clear(key)
                awaitSubscriptionAfter(sourceOfTruth, subscriptionsBeforeClear)
                while (true) {
                    val item = awaitItem()
                    if (item is StoreResult.Data && item.value == "v2") break
                }
                assertEquals(2, fetchCalls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun readerGen_serverDelete_reconnectsRotatingSlotReader() = runTest {
        val sourceOfTruth = RotatingSlotSourceOfTruth<TestKey, String>()
        var fetchCalls = 0
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcherOfResult {
                when (++fetchCalls) {
                    1 -> FetcherResult.Success("v1")
                    2 -> FetcherResult.Deleted
                    else -> error("unexpected fetch call $fetchCalls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(key))
            app.cash.turbine.turbineScope {
                val observer = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("v1", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
                runCurrent()
                val subscriptionsBeforeDelete = sourceOfTruth.subscriptionCount(key)

                val deletion =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { store.get(key, Freshness.MustBeFresh) }
                    }
                runCurrent()
                val failure = assertIs<StoreException>(deletion.await().exceptionOrNull())
                assertIs<StoreError.Missing>(failure.error)
                awaitSubscriptionAfter(sourceOfTruth, subscriptionsBeforeDelete)

                sourceOfTruth.write(key, "v2")
                runCurrent()
                while (true) {
                    val item = observer.awaitItem()
                    if (item is StoreResult.Data && item.value == "v2") break
                }
                assertEquals(2, fetchCalls)
                observer.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    private suspend fun awaitSubscriptionAfter(
        sourceOfTruth: RotatingSlotSourceOfTruth<TestKey, String>,
        previousCount: Int,
    ) {
        // Preserve the real-time Default-dispatch hop and let the suite-level runTest bound own
        // cancellation.
        withContext(Dispatchers.Default) {
            while (sourceOfTruth.subscriptionCount(key) <= previousCount) {
                yield()
            }
        }
        assertTrue(
            sourceOfTruth.subscriptionCount(key) > previousCount,
            "readerGen must attach to the rotated durable slot",
        )
    }
}

// 017 residual-deadline repair: Turbine's 3s default nested inside the 25s shadow; raise the
// Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15).
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
