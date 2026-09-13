package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.KeyEvents
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import org.mobilenativefoundation.store6.core.seam.runtime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class SupersededWatermarkSignalSuppressionTest {
    @Test
    fun invalidateAll_withWatermarkSupersededByLaterSuccess_emitsNoInvalidatedSignals() = runTest {
        val successEntered = CompletableDeferred<Unit>()
        val releaseSuccess = CompletableDeferred<Unit>()
        val durableBookkeeper = RecordingBookkeeper()
        val telemetry = RecordingTelemetry()
        var calls = 0
        val store = store<TestKey, String> {
            fetcherOfResult { FetcherResult.Success("v${++calls}", etag = "e$calls") }
            bookkeeper(durableBookkeeper)
            telemetry(telemetry)
        }
        val runtime = assertNotNull(store.runtime())
        val key = TestKey("1")

        runtime.keyEvents.test {
            assertEquals("v1", store.get(key))
            assertIs<KeyEvents.Written>(awaitItem())
            durableBookkeeper.successEntered = successEntered
            durableBookkeeper.releaseSuccess = releaseSuccess

            val laterSuccess = backgroundScope.async { store.get(key, Freshness.MustBeFresh) }
            testScheduler.runCurrent()
            awaitFromDefault { successEntered.await() }
            val invalidation = backgroundScope.async { store.invalidateAll() }
            testScheduler.runCurrent()
            assertTrue(
                durableBookkeeper.log.contains("advanceGlobalStaleWatermark"),
                "watermark must advance before resident status is rechecked",
            )
            releaseSuccess.complete(Unit)
            assertEquals("v2", awaitFromDefault { laterSuccess.await() })
            assertIs<KeyEvents.Written>(awaitItem())
            awaitFromDefault { invalidation.await() }

            // The later success superseded the watermark: the resident sweep skips the stale
            // mark, so no Invalidated signal may reach the event bus or telemetry.
            expectNoEvents()

            // Positive control: a covered resident still signals on a real invalidation.
            store.invalidate(key)
            assertIs<KeyEvents.Invalidated>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
        assertEquals(
            listOf(
                "start",
                "success",
                "serve:FETCHER",
                "start",
                "success",
                "serve:FETCHER",
                "invalidated",
            ),
            telemetry.events,
        )
    }

    private class RecordingTelemetry : StoreTelemetry {
        val events = mutableListOf<String>()

        override fun onFetchStarted(key: StoreKey) {
            events += "start"
        }

        override fun onFetchSucceeded(
            key: StoreKey,
            duration: Duration,
        ) {
            events += "success"
        }

        override fun onFetchFailed(
            key: StoreKey,
            error: StoreError,
            duration: Duration,
        ) {
            events += "failure"
        }

        override fun onServe(
            key: StoreKey,
            origin: Origin,
        ) {
            events += "serve:$origin"
        }

        override fun onInvalidated(key: StoreKey) {
            events += "invalidated"
        }

        override fun onCleared(key: StoreKey) {
            events += "cleared"
        }
    }

    // Preserve the real-time Default-dispatch hop (never virtual time) and let the suite-level
    // runTest bound own cancellation.
    private suspend fun <T> awaitFromDefault(block: suspend () -> T): T =
        withContext(Dispatchers.Default) {
            block()
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
