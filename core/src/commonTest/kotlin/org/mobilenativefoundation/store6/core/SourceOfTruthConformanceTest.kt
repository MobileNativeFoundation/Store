package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import org.mobilenativefoundation.store6.core.internal.SharedFlowSourceOfTruth
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)
class SourceOfTruthConformanceTest {

    // Values arriving via fetch commit are attributed FETCHER.
    @Test
    fun originHonesty_fetchCommit_emitsFetcher() = runTest {
        var calls = 0
        val key = TestKey("1")
        val readerProbe =
            ReaderDeliveryProbeSourceOfTruth<TestKey, String>(InMemorySourceOfTruth())
        val store =
            store<TestKey, String> {
                fetcher { calls += 1; "v" }
                persistence(readerProbe)
            }
        try {
            turbineScope {
                // Loading precedes shared-reader enrollment. Keep a public LocalOnly observer live,
                // then close the engine-facing delivery edge with the test-only probe.
                val seedReader =
                    store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                val missing = assertIs<StoreResult.Error>(seedReader.awaitItem())
                assertIs<StoreError.Missing>(missing.error)
                assertFalse(missing.servedStale)
                assertEquals(0, calls)
                readerProbe.awaitCurrentReaderFirstDelivery(key)

                val collector = store.stream(key).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(collector.awaitItem())
                val data = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals(Origin.FETCHER, data.origin)
                assertFalse(data.isStale)
                assertFalse(data.refreshing)
                assertEquals(1, calls)
                seedReader.cancelAndIgnoreRemainingEvents()
                collector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.closeAndSettleForTest()
        }
    }

    // External data is delivered as SOT/stale before its one active-demand revalidation.
    @Test
    fun originHonesty_externalSotWrite_emitsSotToActiveStream() = runTest {
        var calls = 0
        val revalidationStarted = CompletableDeferred<Unit>()
        val revalidationGate = CompletableDeferred<Unit>()
        val sot = SharedFlowSourceOfTruth<TestKey, String>()
        val store = store<TestKey, String> {
            fetcher {
                calls++
                if (calls == 1) {
                    "fetched"
                } else {
                    revalidationStarted.complete(Unit)
                    revalidationGate.await()
                    "revalidated"
                }
            }
            persistence(sot)
        }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals("fetched", assertIs<StoreResult.Data<String>>(awaitItem()).value)

            sot.write(TestKey("1"), "external")

            val external = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("external", external.value)
            assertEquals(Origin.SOT, external.origin)
            assertTrue(external.isStale)
            revalidationStarted.await()
            assertEquals(2, calls)
            revalidationGate.complete(Unit)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // The memory fast path serves without waiting for the pipeline, stamped MEMORY.
    @Test
    fun originHonesty_memoryFastPath_reStampsMemory() = runTest {
        val store = store<TestKey, String> { fetcher { "v" } }
        assertEquals("v", store.get(TestKey("1")))
        store.stream(TestKey("1")).test {
            val first = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals(Origin.MEMORY, first.origin)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // The stale-tag interleave: a collector-less commit parks a tag; a later external write must
    // not inherit it (value binding). Guards the SoT-read values-never-labeled-FETCHER criterion.
    @Test
    fun dormantCommit_externalWriteBeforeFirstCollector_attributesSot() = runTest {
        val key = TestKey("1")
        val sot = SharedFlowSourceOfTruth<TestKey, String>()
        val revalidationGate = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val store = store<TestKey, String> {
            fetcher {
                fetchCalls++
                if (fetchCalls == 1) {
                    "fetched"
                } else {
                    revalidationGate.await()
                    "revalidated"
                }
            }
            persistence(sot)
        }
        try {
            assertEquals("fetched", store.get(key))
            sot.write(key, "external")
            val external =
                withContext(Dispatchers.Default) {
                    store.stream(key)
                        .filterIsInstance<StoreResult.Data<String>>()
                        .first { it.value == "external" }
                }
            assertEquals(Origin.SOT, external.origin)
        } finally {
            revalidationGate.complete(Unit)
            store.close()
        }
    }

    // The F-1 killer: invalidate with multiple active collectors on the DSL default SoT.
    @Test
    fun orphanRegression_invalidateWithActiveCollectors_allObserveRefetchedData() = runTest {
        var calls = 0
        val firstFetchGate = CompletableDeferred<Unit>()
        val secondFetchStarted = CompletableDeferred<Unit>()
        val secondFetchGate = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            fetcher {
                val call = ++calls
                when (call) {
                    1 -> firstFetchGate.await()
                    2 -> {
                        secondFetchStarted.complete(Unit)
                        secondFetchGate.await()
                    }
                    else -> error("unexpected fetch call $call")
                }
                "v$call"
            }
        }
        try {
            turbineScope {
                val key = TestKey("1")
                val a = store.stream(key).testIn(backgroundScope)
                val b = store.stream(key).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(a.awaitItem())
                assertIs<StoreResult.Loading>(b.awaitItem())
                // Loading is sent before StreamDelivery.start; drain both collectors while fetch 1
                // is blocked so their initial-ticket watchers are parked before the first commit.
                runCurrent()
                firstFetchGate.complete(Unit)
                assertEquals("v1", assertIs<StoreResult.Data<String>>(a.awaitItem()).value)
                assertEquals("v1", assertIs<StoreResult.Data<String>>(b.awaitItem()).value)

                store.invalidate(key)
                secondFetchStarted.await()
                // Fetch 2 is blocked, so draining the test scheduler causally enrolls both active
                // collectors on that ticket before it can settle and a late watcher can launch I3.
                runCurrent()
                assertEquals(2, calls)
                secondFetchGate.complete(Unit)

                var aSeen = false
                while (!aSeen) {
                    val item = a.awaitItem()
                    aSeen = item is StoreResult.Data<String> && item.value == "v2"
                }
                var bSeen = false
                while (!bSeen) {
                    val item = b.awaitItem()
                    bSeen = item is StoreResult.Data<String> && item.value == "v2"
                }
                assertEquals(2, calls)
                a.cancelAndIgnoreRemainingEvents()
                b.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            firstFetchGate.complete(Unit)
            secondFetchGate.complete(Unit)
            store.close()
        }
    }

    // Resubscribe after clear may duplicate, never lose; cleared value never replays.
    @Test
    fun resubscribeAfterClear_duplicatesNotLosses() = runTest {
        var calls = 0
        val store = store<TestKey, String> { fetcher { calls++; "v$calls" } }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            cancelAndIgnoreRemainingEvents()
        }

        store.clear(TestKey("1"))

        store.stream(TestKey("1")).test {
            var sawLoading = false
            var sawFresh = false
            while (!sawFresh) {
                when (val item = awaitItem()) {
                    is StoreResult.Loading -> sawLoading = true
                    is StoreResult.Data<String> -> {
                        assertTrue(item.value != "v1")
                        if (item.value == "v2") sawFresh = true
                    }
                    else -> Unit
                }
            }
            assertTrue(sawLoading)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // Populate, unsubscribe, outlast the grace, clear, then prove stale replay cannot resurrect.
    @Test
    fun clearWhilePipelineDormant_neverResurrectsClearedValue() = runTest {
        var calls = 0
        val store = store<TestKey, String> { fetcher { calls++; "v$calls" } }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            cancelAndIgnoreRemainingEvents()
        }
        withContext(Dispatchers.Default) { delay(400) }

        store.clear(TestKey("1"))

        store.stream(TestKey("1")).test {
            while (true) {
                val item = awaitItem()
                if (item is StoreResult.Data<String>) {
                    assertEquals("v2", item.value)
                    break
                }
            }
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // Null-on-delete liveness through Store: external delete -> absent transition, stream live.
    @Test
    fun externalSotDelete_activeStreamSeesAbsentTransitionAndStaysLive() = runTest {
        var calls = 0
        val thirdFetchStarted = CompletableDeferred<Unit>()
        val thirdFetchGate = CompletableDeferred<Unit>()
        val sot = SharedFlowSourceOfTruth<TestKey, String>()
        val store = store<TestKey, String> {
            fetcher {
                val call = ++calls
                if (call == 3) {
                    thirdFetchStarted.complete(Unit)
                    thirdFetchGate.await()
                }
                "fetched-$call"
            }
            persistence(sot)
        }
        try {
            store.stream(TestKey("1")).test {
                assertIs<StoreResult.Loading>(awaitItem())
                assertEquals("fetched-1", assertIs<StoreResult.Data<String>>(awaitItem()).value)

                sot.delete(TestKey("1"))

                assertIs<StoreResult.Loading>(awaitItem())
                while (true) {
                    val item = awaitItem()
                    if (item is StoreResult.Data) {
                        assertEquals("fetched-2", item.value)
                        assertEquals(Origin.FETCHER, item.origin)
                        assertFalse(item.isStale)
                        break
                    }
                }
                assertEquals(2, calls)
                sot.write(TestKey("1"), "rewritten")
                // Keep the valid null-meta revalidation from overtaking the liveness probe.
                thirdFetchStarted.await()
                while (true) {
                    val item = awaitItem()
                    if (item is StoreResult.Data && item.value == "rewritten") {
                        assertEquals(Origin.SOT, item.origin)
                        assertTrue(item.isStale)
                        break
                    }
                }
                assertEquals(3, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            thirdFetchGate.complete(Unit)
            store.close()
        }
    }

    // A pre-populated SoT serves without a fetch under LocalOnly.
    @Test
    fun localOnly_prePopulatedSot_getServesWithoutFetcher() = runTest {
        var calls = 0
        val sot = SharedFlowSourceOfTruth<TestKey, String>()
        sot.write(TestKey("1"), "durable")
        val store = store<TestKey, String> {
            fetcher { calls++; "fetched" }
            persistence(sot)
        }
        assertEquals("durable", store.get(TestKey("1"), Freshness.LocalOnly))
        assertEquals(0, calls)
        store.close()
    }

    // Hydration: unknown provenance serves and triggers exactly one revalidation.
    @Test
    fun cachedOrFetch_hydratedRow_servesThenRevalidatesExactlyOnce() = runTest {
        var calls = 0
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val sot = SharedFlowSourceOfTruth<TestKey, String>()
        sot.write(TestKey("1"), "durable")
        val store = store<TestKey, String> {
            fetcher {
                calls++
                fetchStarted.complete(Unit)
                releaseFetch.await()
                "fetched"
            }
            persistence(sot)
        }
        try {
            assertEquals("durable", store.get(TestKey("1")))
            fetchStarted.await()
            assertEquals(1, calls)
            store.stream(TestKey("1")).test {
                val hydrated = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("durable", hydrated.value)
                assertTrue(hydrated.refreshing)

                releaseFetch.complete(Unit)
                while (true) {
                    val item = awaitItem()
                    if (item is StoreResult.Data<String> && item.value == "fetched") break
                }
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals("fetched", store.get(TestKey("1")))
            assertEquals(1, calls)
        } finally {
            releaseFetch.complete(Unit)
            store.close()
        }
    }

    // Persisted truth participates in startup before its revalidation can overwrite it.
    @Test
    fun cachedOrFetch_prePopulatedSot_streamServesSotBeforeRevalidation() = runTest {
        var calls = 0
        val fetchStarted = CompletableDeferred<Unit>()
        val fetchGate = CompletableDeferred<Unit>()
        val sot = SharedFlowSourceOfTruth<TestKey, String>()
        sot.write(TestKey("1"), "durable")
        val store = store<TestKey, String> {
            fetcher {
                calls++
                fetchStarted.complete(Unit)
                fetchGate.await()
                "fetched"
            }
            persistence(sot)
        }
        store.stream(TestKey("1")).test {
            val durable = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("durable", durable.value)
            assertEquals(Origin.SOT, durable.origin)
            assertTrue(durable.isStale)
            assertTrue(durable.refreshing)

            fetchStarted.await()
            fetchGate.complete(Unit)
            val fetched = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("fetched", fetched.value)
            assertEquals(Origin.FETCHER, fetched.origin)
            assertEquals(1, calls)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // I7: a 304 refreshes metadata and emits Revalidated to the revalidating collector.
    @Test
    fun notModified_refreshesMetaAndEmitsRevalidated() = runTest {
        var calls = 0
        val store = store<TestKey, String> {
            fetcherOfResult {
                calls++
                if (calls == 1) {
                    FetcherResult.Success("v1", etag = "e1")
                } else {
                    FetcherResult.NotModified(etag = "e1")
                }
            }
        }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)

            store.invalidate(TestKey("1"))

            var item = awaitItem()
            while (item is StoreResult.Data<String> && item.isStale) {
                item = awaitItem()
            }
            assertIs<StoreResult.Revalidated>(item)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // I5 enforcement: exercise every writeLock-holding path under concurrency.
    @Test
    fun lockOrderCanary_concurrentCommitClearStreamAndGet_terminates() = runTest {
        var calls = 0
        val store = store<TestKey, String> { fetcher { calls++; "v$calls" } }
        suspend fun getAllowingConcurrentClear() {
            try {
                store.get(TestKey("1"))
            } catch (failure: StoreException) {
                assertIs<StoreError.Missing>(failure.error)
            }
        }
        withContext(Dispatchers.Default) {
            val collector = launch {
                store.stream(TestKey("1")).collect { }
            }
            repeat(10) {
                getAllowingConcurrentClear()
                store.invalidate(TestKey("1"))
                getAllowingConcurrentClear()
                store.clear(TestKey("1"))
            }
            collector.cancel()
        }
        store.close()
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
