package org.mobilenativefoundation.store6.testing

import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.*
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

/**
 * Read-core conformance coverage for [FakeStore].
 *
 * | Posture | Behaviors |
 * |---|---|
 * | Applicable | Cold-stream sequence, value/error channels, replay, resident serve, one-time
 * script consumption, stale-get SWR, demand-deferred invalidation, clear transitions,
 * Revalidated cycles, namespace isolation, close semantics, clock-derived age, and
 * lifecycle-frame survival through StateFlow/stateIn. |
 * | Engine-only | Fetch cancellation and non-cooperative fetchers, freshness-policy matrices,
 * dispatcher contention, backpressure conflation, overlay projection, key events, and runtime
 * access. Test those by composing the seam fakes into a real store. |
 *
 * There is no invalidate-divergence row: the fake is aligned to the engine's stale-mark-only,
 * next-demand consumption posture.
 */
@OptIn(ExperimentalStoreApi::class)
class FakeStoreConformanceTest {
    private val key = TestingKey("test", "1")

    @Test
    fun coldStream_absentKey_emitsLoadingThenScriptedDataFromFetcher() = runTest {
        val store = FakeStore<TestingKey, String>()
        store.enqueueFetchValue(key, "v")
        store.stream(key).test {
            assertIs<StoreResult.Loading>(awaitItem())
            val data = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("v", data.value)
            assertEquals(Origin.FETCHER, data.origin)
            expectNoEvents() // live, not completed
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun scriptedError_streamEmitsErrorAndStaysLive_neverThrows() = runTest {
        val store = FakeStore<TestingKey, String>()
        store.enqueueFetchError(key, TestStoreResults.fetchError("fetch test/1 failed: scripted failure. Adjust the script."))
        store.stream(key).test {
            assertIs<StoreResult.Loading>(awaitItem())
            val error = assertIs<StoreResult.Error>(awaitItem())
            assertIs<StoreError.Fetch>(error.error)
            assertFalse(error.servedStale)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun scriptedError_getThrowsStoreExceptionWithSameCategory() = runTest {
        val store = FakeStore<TestingKey, String>()
        store.enqueueFetchError(key, TestStoreResults.fetchError("fetch test/1 failed: scripted failure. Adjust the script."))
        val ex = assertFailsWith<StoreException> { store.get(key) }
        assertIs<StoreError.Fetch>(ex.error)
        store.close()
    }

    @Test
    fun absentKey_noScript_getThrowsMissingWithFixMessage() = runTest {
        val store = FakeStore<TestingKey, String>()
        val ex = assertFailsWith<StoreException> { store.get(key) }
        assertIs<StoreError.Missing>(ex.error)
        assertTrue(ex.message!!.contains("test/1"))            // which key
        assertTrue(ex.message!!.contains("enqueueFetchValue"))  // the fix
        store.close()
    }

    @Test
    fun residentValue_lateCollector_receivesDataImmediatelyWithoutLoading() = runTest {
        val store = FakeStore<TestingKey, String>()
        store.setValue(key, "v")
        store.stream(key).test {
            val first = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("v", first.value)
            assertEquals(Origin.MEMORY, first.origin)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun freshResident_getServesResidentWithoutConsumingScript() = runTest {
        val store = FakeStore<TestingKey, String>()
        store.setValue(key, "resident")
        store.enqueueFetchValue(key, "scripted")
        assertEquals("resident", store.get(key))
        store.clear(key)
        assertEquals("scripted", store.get(key)) // script only consumed on the post-clear demand
        store.close()
    }

    @Test
    fun staleResident_getServesStaleThenConsumesRefresh() = runTest {
        val store = FakeStore<TestingKey, String>()
        store.setValue(key, "v1", isStale = true)
        store.enqueueFetchValue(key, "v2")
        assertEquals("v1", store.get(key)) // SWR mirror: stale served immediately, refresh consumed behind
        assertEquals("v2", store.get(key)) // the consumed refresh committed
        store.close()
    }

    @Test
    fun twoConcurrentCollectors_oneScriptedOutcomeConsumedOnce() = runTest {
        val store = FakeStore<TestingKey, String>()
        store.setValue(key, "v1", isStale = true)
        store.enqueueFetchError(key, TestStoreResults.fetchError("first scripted failure"))
        store.enqueueFetchValue(key, "v2")
        val aReady = CompletableDeferred<Unit>()
        val bReady = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val a =
            backgroundScope.async {
                store.stream(key)
                    .onEach {
                        if (it is StoreResult.Data && it.isStale) {
                            aReady.complete(Unit)
                            release.await()
                        }
                    }.take(2)
                    .toList()
            }
        val b =
            backgroundScope.async {
                store.stream(key)
                    .onEach {
                        if (it is StoreResult.Data && it.isStale) {
                            bReady.complete(Unit)
                            release.await()
                        }
                    }.take(2)
                    .toList()
            }
        aReady.await()
        bReady.await()
        release.complete(Unit)
        val aResults = a.await()
        val bResults = b.await()
        assertIs<StoreResult.Error>(aResults.single { it is StoreResult.Error })
        assertIs<StoreResult.Error>(bResults.single { it is StoreResult.Error })
        assertEquals("v1", store.get(key)) // the one winning demand consumed only the failure
        assertEquals("v2", store.get(key)) // the next demand consumed the retained value
        store.close()
    }

    @Test
    fun invalidate_activeStream_observesStaleDataThenScriptedRefetch_honestFlags() = runTest {
        // The ACTIVE collector IS demand (the engine's active-stream replan analog) — it
        // observes the stale frame, then drives consumption of the queued script.
        val store = FakeStore<TestingKey, String>()
        store.setValue(key, "v1")
        store.stream(key).test {
            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            store.enqueueFetchValue(key, "v2")
            store.invalidate(key)
            val stale = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("v1", stale.value)
            assertTrue(stale.isStale)
            assertTrue(stale.refreshing)
            val fresh = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("v2", fresh.value)
            assertFalse(fresh.isStale)
            assertFalse(fresh.refreshing)
            assertEquals(Origin.FETCHER, fresh.origin)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun invalidate_withoutActiveDemand_getServesStaleThenRefreshCommitsBehind() = runTest {
        // With NO active demand,
        // invalidate defers scripted-staleness consumption to the next demand — engine parity:
        // get-after-invalidate serves the STALE value and fires the SWR refresh behind it. Under
        // the rejected eager design the first get would have returned "v2".
        val store = FakeStore<TestingKey, String>()
        store.setValue(key, "v1")
        store.enqueueFetchValue(key, "v2")
        store.invalidate(key)               // stale-mark only; script NOT consumed
        assertEquals("v1", store.get(key))  // stale served; consumption happens behind this read
        assertEquals("v2", store.get(key))  // the refresh committed
        store.close()
    }

    @Test
    fun invalidate_withoutActiveDemand_scriptRetainedForNextCollector() = runTest {
        // The deferred script is consumed by the NEXT stream demand, which first observes the
        // honest stale snapshot (isStale=true, refreshing=true because a script waits).
        val store = FakeStore<TestingKey, String>()
        store.setValue(key, "v1")
        store.enqueueFetchValue(key, "v2")
        store.invalidate(key)
        store.stream(key).test {
            val stale = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("v1", stale.value)
            assertTrue(stale.isStale)
            assertTrue(stale.refreshing)
            assertEquals("v2", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun invalidate_noScript_staleMarkOnly_noConsumptionNoError() = runTest {
        // Invalidate with an empty script is a pure stale-mark (epoch-bump analog);
        // active collectors observe isStale=true, refreshing=false, and nothing else happens.
        val store = FakeStore<TestingKey, String>()
        store.setValue(key, "v1")
        store.stream(key).test {
            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            store.invalidate(key)
            val stale = assertIs<StoreResult.Data<String>>(awaitItem())
            assertTrue(stale.isStale)
            assertFalse(stale.refreshing)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("v1", store.get(key)) // stale resident still served; no script to consume
        store.close()
    }

    @Test
    fun invalidate_activeDemand_consumesOnlyOneFailure_thenRetainsNextScript() = runTest {
        val store = FakeStore<TestingKey, String>()
        store.setValue(key, "v1")
        store.stream(key).test {
            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            store.enqueueFetchError(key, TestStoreResults.fetchError("first scripted failure"))
            store.enqueueFetchValue(key, "v2")
            store.invalidate(key)
            assertTrue(assertIs<StoreResult.Data<String>>(awaitItem()).isStale)
            assertIs<StoreResult.Error>(awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("v1", store.get(key))
        assertEquals("v2", store.get(key))
        store.close()
    }

    @Test
    fun revalidated_withResident_streamEmitsRevalidatedAndRefreshesFreshness() = runTest {
        val store = FakeStore<TestingKey, String>()
        store.setValue(key, "v")
        store.wallClock.advanceBy(5.minutes)
        store.enqueueFetchRevalidated(key, age = 5.minutes)
        store.stream(key).test {
            assertEquals(5.minutes, assertIs<StoreResult.Data<String>>(awaitItem()).age)
            store.invalidate(key)
            assertTrue(assertIs<StoreResult.Data<String>>(awaitItem()).isStale) // stale frame first
            assertEquals(5.minutes, assertIs<StoreResult.Revalidated>(awaitItem()).age) // active demand consumed it
            cancelAndIgnoreRemainingEvents()
        }
        store.wallClock.advanceBy(2.minutes)
        store.stream(key).test {
            val data = assertIs<StoreResult.Data<String>>(awaitItem())
            assertFalse(data.isStale)          // 304 cleared staleness
            assertEquals(2.minutes, data.age)  // age from the refreshed writtenAt
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun revalidated_withoutResident_isMissingOnBothChannels() = runTest {
        val store = FakeStore<TestingKey, String>()
        store.enqueueFetchRevalidated(key)
        store.stream(key).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertIs<StoreError.Missing>(assertIs<StoreResult.Error>(awaitItem()).error)
            cancelAndIgnoreRemainingEvents()
        }
        store.enqueueFetchRevalidated(key)
        val ex = assertFailsWith<StoreException> { store.get(key) }
        assertIs<StoreError.Missing>(ex.error)
        store.close()
    }

    @Test
    fun clear_activeStream_observesAbsentTransition() = runTest {
        val store = FakeStore<TestingKey, String>()
        store.setValue(key, "v1")
        store.stream(key).test {
            assertIs<StoreResult.Data<String>>(awaitItem())
            store.clear(key)
            assertIs<StoreResult.Loading>(awaitItem()) // honest absent transition
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun namespaceSweeps_touchOnlyMatchingNamespace() = runTest {
        val store = FakeStore<TestingKey, String>()
        val other = TestingKey("other", "1")
        store.setValue(key, "a")
        store.setValue(other, "b")
        store.clearNamespace(StoreNamespace("test"))
        assertFailsWith<StoreException> { store.get(key) }
        assertEquals("b", store.get(other))
        store.invalidateNamespace(StoreNamespace("other"))
        store.stream(other).test {
            assertTrue(assertIs<StoreResult.Data<String>>(awaitItem()).isStale)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun lifecycleFrames_surviveStateFlowConsumer() = runTest {
        // Pins per-frame dispatch: a stateIn consumer (the dominant ViewModel shape) observes
        // every lifecycle frame — the StateFlow-conflation trap cannot swallow the
        // stale/refreshing frame. The eagerly-shared collector is ACTIVE demand, so it drives
        // consumption of the queued script after the stale frame.
        val store = FakeStore<TestingKey, String>()
        store.setValue(key, "v1")
        val state: StateFlow<StoreResult<String>?> =
            store.stream(key).stateIn(backgroundScope, SharingStarted.Eagerly, null)
        state.filterNotNull().test {
            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            store.enqueueFetchError(key, TestStoreResults.fetchError("refresh test/1 failed: scripted. Adjust the script."))
            store.invalidate(key)
            val stale = assertIs<StoreResult.Data<String>>(awaitItem())
            assertTrue(stale.isStale)
            assertTrue(stale.refreshing)
            assertIs<StoreResult.Error>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun staleBeforeCollection_lifecycleFramesSurviveStateFlowConsumer() = runTest {
        val store = FakeStore<TestingKey, String>()
        store.setValue(key, "v1")
        store.enqueueFetchValue(key, "v2")
        store.invalidate(key)
        val state: StateFlow<StoreResult<String>?> =
            store.stream(key).stateIn(backgroundScope, SharingStarted.Lazily, null)
        state.filterNotNull().test {
            val stale = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("v1", stale.value)
            assertTrue(stale.isStale)
            assertTrue(stale.refreshing)
            val fresh = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("v2", fresh.value)
            assertFalse(fresh.isStale)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun close_isIdempotent_andOperationsAfterCloseFailFast() = runTest {
        // Pins verified against StoreCloseLifecycleTest in core.
        val store = FakeStore<TestingKey, String>()
        store.close()
        store.close() // no additional effect
        assertEquals("Store is closed.", assertFailsWith<IllegalStateException> { store.get(key) }.message)
        assertEquals("Store is closed.", assertFailsWith<IllegalStateException> { store.stream(key) }.message)
        assertEquals(1, store.interactions.filterIsInstance<FakeStoreInteraction.Close>().size)
    }

    @Test
    fun close_cancelsActiveCollectors() = runTest {
        // Pins verified against StoreCloseLifecycleTest in core.
        val store = FakeStore<TestingKey, String>()
        store.setValue(key, "v")
        val started = CompletableDeferred<Unit>()
        val collector = backgroundScope.async {
            runCatching { store.stream(key).collect { started.complete(Unit) } }
        }
        started.await()
        store.close()
        val failure = collector.await().exceptionOrNull()
        assertIs<CancellationException>(failure)
        assertEquals("Store is closed.", failure.message)
    }

    @Test
    fun age_derivesFromTestWallClock() = runTest {
        val store = FakeStore<TestingKey, String>()
        store.setValue(key, "v")
        store.wallClock.advanceBy(5.minutes)
        store.stream(key).test {
            assertEquals(5.minutes, assertIs<StoreResult.Data<String>>(awaitItem()).age)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }
}
