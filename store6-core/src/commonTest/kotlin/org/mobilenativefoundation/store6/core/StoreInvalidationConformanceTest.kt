package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

open class StoreInvalidationConformanceTest : SourceOfTruthSubstitutionTest() {
    // An active stream signaled by invalidate observes refetched data.
    @Test
    fun invalidate_activeStream_observesRefetchedData() = runTest {
        var calls = 0
        val secondFetchStarted = CompletableDeferred<Unit>()
        val releaseSecondFetch = CompletableDeferred<Unit>()
        val key = TestKey("1")
        val store = testStore<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondFetchStarted.complete(Unit)
                        releaseSecondFetch.await()
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            val seedReader =
                store.awaitLocalOnlyMissingReaderBarrier(key, backgroundScope)
            assertEquals(0, calls)
            awaitCurrentReaderFirstDelivery(key)

            store.stream(key).test {
                assertIs<StoreResult.Loading>(awaitItem())
                val initial = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v1", initial.value)
                assertFalse(initial.isStale)
                assertFalse(initial.refreshing)
                seedReader.cancel()

                store.invalidate(key)
                secondFetchStarted.awaitFromDefault()
                releaseSecondFetch.complete(Unit)

                var fresh = assertIs<StoreResult.Data<String>>(awaitItem())
                var queuedStaleReplays = 0
                while (fresh.value == "v1") {
                    queuedStaleReplays += 1
                    assertEquals(1, queuedStaleReplays, "more than one queued stale replay")
                    assertTrue(fresh.isStale)
                    assertTrue(fresh.refreshing)
                    fresh = assertIs<StoreResult.Data<String>>(awaitItem())
                }
                assertEquals("v2", fresh.value)
                assertFalse(fresh.isStale)
                assertFalse(fresh.refreshing)
                expectNoEvents()
                assertEquals(2, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseSecondFetch.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    // Pinned SWR posture: get on a stale resident serves stale now and refetches in background.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun getOnStaleResident_servesStaleThenRefetchesInBackground() = runTest {
        var calls = 0
        val secondFetchStarted = CompletableDeferred<Unit>()
        val releaseSecondFetch = CompletableDeferred<Unit>()
        val key = TestKey("1")
        val store = testStore<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondFetchStarted.complete(Unit)
                        releaseSecondFetch.await()
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }
        try {
            val seedReader =
                store.awaitLocalOnlyMissingReaderBarrier(key, backgroundScope)
            assertEquals(0, calls)
            awaitCurrentReaderFirstDelivery(key)

            turbineScope {
                val initialCollector = store.stream(key).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(initialCollector.awaitItem())
                val initial = assertIs<StoreResult.Data<String>>(initialCollector.awaitItem())
                assertEquals("v1", initial.value)
                assertFalse(initial.isStale)
                assertFalse(initial.refreshing)
                seedReader.cancel()

                store.invalidate(key)

                assertEquals("v1", store.get(key)) // stale served immediately, not blocked
                secondFetchStarted.awaitFromDefault()
                val collector = store.stream(key).testIn(backgroundScope)
                val stale = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("v1", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)

                // The stale frame precedes this collector's ticket-watcher enrollment. Drain its
                // continuation while fetch 2 is gated so the collector joins before settlement.
                runCurrent()
                releaseSecondFetch.complete(Unit)
                var fresh = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                var queuedStaleReplays = 0
                while (fresh.value == "v1") {
                    queuedStaleReplays += 1
                    assertEquals(1, queuedStaleReplays, "more than one queued stale replay")
                    assertTrue(fresh.isStale)
                    assertTrue(fresh.refreshing)
                    fresh = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                }
                assertEquals("v2", fresh.value)
                assertFalse(fresh.isStale)
                assertFalse(fresh.refreshing)
                collector.expectNoEvents()
                initialCollector.cancelAndIgnoreRemainingEvents()
                collector.cancelAndIgnoreRemainingEvents()
            }
            assertEquals("v2", store.get(key))
            assertEquals(2, calls) // background refetch and stream fetch single-flighted
        } finally {
            releaseSecondFetch.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    // Honesty of age / isStale / refreshing on emissions.
    @Test
    fun staleResident_newCollector_seesHonestFlagsThenFreshData() = runTest {
        var calls = 0
        val secondStarted = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
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
            val seedReader =
                store.awaitLocalOnlyMissingReaderBarrier(key, backgroundScope)
            assertEquals(0, calls)
            awaitCurrentReaderFirstDelivery(key)

            turbineScope {
                val initialCollector = store.stream(key).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(initialCollector.awaitItem())
                val initial = assertIs<StoreResult.Data<String>>(initialCollector.awaitItem())
                assertEquals("v1", initial.value)
                assertFalse(initial.isStale)
                assertFalse(initial.refreshing)
                seedReader.cancel()

                store.invalidate(key)
                secondStarted.awaitFromDefault()

                val collector = store.stream(key).testIn(backgroundScope)
                val stale = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("v1", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)
                assertTrue(stale.age >= Duration.ZERO)

                secondGate.complete(Unit)

                var fresh = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                var queuedStaleReplays = 0
                while (fresh.value == "v1") {
                    queuedStaleReplays += 1
                    assertEquals(1, queuedStaleReplays, "more than one queued stale replay")
                    assertTrue(fresh.isStale)
                    assertTrue(fresh.refreshing)
                    fresh = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                }
                assertEquals("v2", fresh.value)
                assertFalse(fresh.isStale)
                assertFalse(fresh.refreshing)
                collector.expectNoEvents()
                assertEquals(2, calls)
                initialCollector.cancelAndIgnoreRemainingEvents()
                collector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            secondGate.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    // Clear on an active stream: absent transition (Loading), then refetched data, never stale replay.
    @Test
    fun clear_activeStream_emitsLoadingThenRefetchedData() = runTest {
        var calls = 0
        val secondFetchStarted = CompletableDeferred<Unit>()
        val releaseSecondFetch = CompletableDeferred<Unit>()
        val key = TestKey("1")
        val store = testStore<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondFetchStarted.complete(Unit)
                        releaseSecondFetch.await() // hold the refetch so Loading is observable
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            val seedReader =
                store.awaitLocalOnlyMissingReaderBarrier(key, backgroundScope)
            assertEquals(0, calls)
            awaitCurrentReaderFirstDelivery(key)

            store.stream(key).test {
                assertIs<StoreResult.Loading>(awaitItem())
                assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                seedReader.cancel()

                prepareNextReaderDelivery(key)
                store.clear(key)

                assertIs<StoreResult.Loading>(awaitItem()) // honest absent transition
                secondFetchStarted.awaitFromDefault()
                awaitCurrentReaderFirstDelivery(key)
                assertEquals(2, calls)
                releaseSecondFetch.complete(Unit)
                assertEquals("v2", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                expectNoEvents()
                assertEquals(2, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseSecondFetch.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    // Clear during an in-flight fetch discards the commit; no resurrection.
    @Test
    fun clearDuringInFlightFetch_commitDiscarded_noResurrection() = runTest {
        var calls = 0
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcher {
                calls++
                if (calls == 1) {
                    firstStarted.complete(Unit)
                    firstGate.await()
                    "doomed-v1"
                } else {
                    "v$calls"
                }
            }
        }

        try {
            val waiter = backgroundScope.async { runCatching { store.get(TestKey("1")) } }
            firstStarted.awaitFromDefault()

            store.clear(TestKey("1"))
            firstGate.complete(Unit)

            val failure =
                withContext(Dispatchers.Default) {
                    waiter.await()
                }.exceptionOrNull()
            val exception = assertIs<StoreException>(failure)
            val missing = assertIs<StoreError.Missing>(exception.error)
            assertEquals("1", missing.key.canonicalId())
            assertTrue(exception.message!!.contains("test/1")) // which key
            assertTrue(exception.message!!.contains("clear")) // what happened

            assertEquals("v2", store.get(TestKey("1"))) // fresh fetch, never "doomed-v1"
        } finally {
            firstGate.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun clearDuringInFlightFetch_thatFails_waiterObservesMissing() = runTest {
        val fetchStarted = CompletableDeferred<Unit>()
        val fetchGate = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcher {
                fetchStarted.complete(Unit)
                fetchGate.await()
                error("fetch failed after clear")
            }
        }

        try {
            val waiter = backgroundScope.async { runCatching { store.get(TestKey("1")) } }
            fetchStarted.awaitFromDefault()

            store.clear(TestKey("1"))
            fetchGate.complete(Unit)

            val failure =
                withContext(Dispatchers.Default) {
                    waiter.await()
                }.exceptionOrNull()
            val exception = assertIs<StoreException>(failure)
            assertIs<StoreError.Missing>(exception.error)
            assertTrue(exception.message!!.contains("test/1"))
            assertTrue(exception.message!!.contains("clear"))
        } finally {
            fetchGate.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun invalidateNamespace_touchesOnlyMatchingNamespace() = runTest {
        var aCalls = 0
        var bCalls = 0
        val a2Started = CompletableDeferred<Unit>()
        val a2Gate = CompletableDeferred<Unit>()
        val keyA = NamespacedTestKey("a", "1")
        val keyB = NamespacedTestKey("b", "1")
        val store = testStore<NamespacedTestKey, String> {
            fetcher { key ->
                if (key.namespace.value == "a") {
                    when (++aCalls) {
                        1 -> "a1"
                        2 -> {
                            a2Started.complete(Unit)
                            a2Gate.await()
                            "a2"
                        }
                        else -> error("unexpected namespace-a fetch call $aCalls")
                    }
                } else {
                    when (++bCalls) {
                        1 -> "b1"
                        else -> error("unexpected namespace-b fetch call $bCalls")
                    }
                }
            }
        }

        try {
            val seedReaderA =
                store.awaitLocalOnlyMissingReaderBarrier(keyA, backgroundScope)
            val seedReaderB =
                store.awaitLocalOnlyMissingReaderBarrier(keyB, backgroundScope)
            assertEquals(0, aCalls)
            assertEquals(0, bCalls)
            awaitCurrentReaderFirstDelivery(keyA)
            awaitCurrentReaderFirstDelivery(keyB)

            turbineScope {
                val initialCollector = store.stream(keyA).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(initialCollector.awaitItem())
                val initial = assertIs<StoreResult.Data<String>>(initialCollector.awaitItem())
                assertEquals("a1", initial.value)
                assertFalse(initial.isStale)
                assertFalse(initial.refreshing)
                assertEquals("b1", store.get(keyB))
                seedReaderA.cancel()
                seedReaderB.cancel()

                store.invalidateNamespace(StoreNamespace("a"))
                a2Started.awaitFromDefault()

                assertEquals("b1", store.get(keyB)) // untouched, no refetch
                assertEquals(1, bCalls)
                assertEquals("a1", store.get(keyA)) // stale served immediately

                val collector = store.stream(keyA).testIn(backgroundScope)
                val stale = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("a1", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)
                a2Gate.complete(Unit)
                var fresh = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                var queuedStaleReplays = 0
                while (fresh.value == "a1") {
                    queuedStaleReplays += 1
                    assertEquals(1, queuedStaleReplays, "more than one queued stale replay")
                    assertTrue(fresh.isStale)
                    assertTrue(fresh.refreshing)
                    fresh = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                }
                assertEquals("a2", fresh.value)
                assertFalse(fresh.isStale)
                assertFalse(fresh.refreshing)
                collector.expectNoEvents()
                initialCollector.cancelAndIgnoreRemainingEvents()
                collector.cancelAndIgnoreRemainingEvents()
            }
            assertEquals(2, aCalls)
        } finally {
            a2Gate.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun invalidateNamespace_wakesOnlyMatchingResidentCollector() = runTest {
        var aCalls = 0
        var bCalls = 0
        val aRefreshStarted = CompletableDeferred<Unit>()
        val releaseARefresh = CompletableDeferred<Unit>()
        val keyA = NamespacedTestKey("a", "1")
        val keyB = NamespacedTestKey("b", "1")
        val store = testStore<NamespacedTestKey, String> {
            fetcher { key ->
                if (key.namespace.value == "a") {
                    when (++aCalls) {
                        1 -> "a1"
                        2 -> {
                            aRefreshStarted.complete(Unit)
                            releaseARefresh.await()
                            "a2"
                        }
                        else -> error("unexpected namespace-a fetch call $aCalls")
                    }
                } else {
                    when (++bCalls) {
                        1 -> "b1"
                        else -> error("unexpected namespace-b fetch call $bCalls")
                    }
                }
            }
        }

        try {
            val seedReaderA =
                store.awaitLocalOnlyMissingReaderBarrier(keyA, backgroundScope)
            val seedReaderB =
                store.awaitLocalOnlyMissingReaderBarrier(keyB, backgroundScope)
            assertEquals(0, aCalls)
            assertEquals(0, bCalls)
            awaitCurrentReaderFirstDelivery(keyA)
            awaitCurrentReaderFirstDelivery(keyB)

            turbineScope {
                val aCollector = store.stream(keyA).testIn(backgroundScope)
                val bCollector = store.stream(keyB).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(aCollector.awaitItem())
                assertEquals("a1", assertIs<StoreResult.Data<String>>(aCollector.awaitItem()).value)
                assertIs<StoreResult.Loading>(bCollector.awaitItem())
                assertEquals("b1", assertIs<StoreResult.Data<String>>(bCollector.awaitItem()).value)
                seedReaderA.cancel()
                seedReaderB.cancel()

                store.invalidateNamespace(StoreNamespace("a"))
                aRefreshStarted.awaitFromDefault()
                bCollector.expectNoEvents()
                assertEquals(1, bCalls)

                releaseARefresh.complete(Unit)
                while (true) {
                    val item = aCollector.awaitItem()
                    if (item is StoreResult.Data<String> && item.value == "a2") break
                }
                bCollector.expectNoEvents()
                assertEquals(2, aCalls)
                assertEquals(1, bCalls)
                aCollector.cancelAndIgnoreRemainingEvents()
                bCollector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseARefresh.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun invalidateAll_wakesResidentCollector() = runTest {
        var calls = 0
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        refreshStarted.complete(Unit)
                        releaseRefresh.await()
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            val key = TestKey("1")
            val seedReader =
                store.awaitLocalOnlyMissingReaderBarrier(key, backgroundScope)
            assertEquals(0, calls)
            awaitCurrentReaderFirstDelivery(key)

            store.stream(key).test {
                assertIs<StoreResult.Loading>(awaitItem())
                assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                seedReader.cancel()

                store.invalidateAll()
                refreshStarted.awaitFromDefault()
                releaseRefresh.complete(Unit)
                while (true) {
                    val item = awaitItem()
                    if (item is StoreResult.Data<String> && item.value == "v2") break
                }
                assertEquals(2, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseRefresh.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun clearNamespace_deletesAffectedRowsAndKeepsOtherNamespace() = runTest {
        var calls = 0
        val keyA = NamespacedTestKey("a", "1")
        val keyB = NamespacedTestKey("b", "1")
        val store = testStore<NamespacedTestKey, String> { fetcher { "v${++calls}" } }

        try {
            assertEquals("v1", store.get(keyA))
            assertEquals("v2", store.get(keyB))
            store.clearNamespace(StoreNamespace("a"))

            assertEquals("v2", store.get(keyB, Freshness.LocalOnly))
            val missing = assertFailsWith<StoreException> {
                store.get(keyA, Freshness.LocalOnly)
            }
            assertIs<StoreError.Missing>(missing.error)
            assertEquals("v3", store.get(keyA))
            assertEquals(3, calls)
        } finally {
            store.closeAndSettleForTest()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun clear_thenNewStreamEmitsLoadingNeverStaleReplay() = runTest {
        var calls = 0
        val refetchStarted = CompletableDeferred<Unit>()
        val releaseRefetch = CompletableDeferred<Unit>()
        val key = TestKey("1")
        val store = testStore<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        refetchStarted.complete(Unit)
                        releaseRefetch.await()
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(key))
            prepareNextReaderDelivery(key)
            store.clear(key)
            store.stream(key).test {
                assertIs<StoreResult.Loading>(awaitItem())
                refetchStarted.awaitFromDefault()
                // The initial Loading precedes ticket-watcher enrollment. Drain that continuation
                // while fetch 2 remains gated so post-clear demand joins before the outcome settles.
                runCurrent()
                awaitCurrentReaderFirstDelivery(key)
                assertEquals(2, calls)
                releaseRefetch.complete(Unit)
                awaitDataValue(expected = "v2")
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(2, calls)
        } finally {
            releaseRefetch.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun clearNamespace_activeLocalOnlyStreamObservesMissingWithoutRefetch() = runTest {
        var calls = 0
        val key = NamespacedTestKey("a", "1")
        val store =
            testStore<NamespacedTestKey, String> {
                fetcher { "v${++calls}" }
            }

        try {
            assertEquals("v1", store.get(key))
            turbineScope {
                val collector =
                    store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                val resident = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("v1", resident.value)
                assertFalse(resident.isStale)
                assertFalse(resident.refreshing)
                awaitCurrentReaderFirstDelivery(key)

                store.clearNamespace(StoreNamespace("a"))

                // Fenced clear: an already-active pipeline may queue one duplicate pre-clear
                // Data frame. Drain it exactly; Loading then Missing must still follow.
                var frame = collector.awaitItem()
                var queuedPreClearReplays = 0
                while (frame !is StoreResult.Loading) {
                    queuedPreClearReplays += 1
                    assertEquals(1, queuedPreClearReplays, "more than one queued pre-clear replay")
                    val replay = assertIs<StoreResult.Data<String>>(frame)
                    assertEquals("v1", replay.value)
                    assertFalse(replay.isStale)
                    assertFalse(replay.refreshing)
                    frame = collector.awaitItem()
                }
                val missing = assertIs<StoreResult.Error>(collector.awaitItem())
                assertIs<StoreError.Missing>(missing.error)
                assertFalse(missing.servedStale)
                assertEquals(1, calls)
                collector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.closeAndSettleForTest()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun clearNamespace_thenNewStreamEmitsLoadingNeverPreClearData() = runTest {
        var calls = 0
        val refetchStarted = CompletableDeferred<Unit>()
        val releaseRefetch = CompletableDeferred<Unit>()
        val key = NamespacedTestKey("a", "1")
        val store = testStore<NamespacedTestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        refetchStarted.complete(Unit)
                        releaseRefetch.await()
                        "v2"
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(key))
            turbineScope {
                // No shared reader is active during the two bulk-clear sweeps. A retained
                // post-clear LocalOnly observer then starts directly from the final generation,
                // making its downstream null-delivery acknowledgement unambiguous.
                store.clearNamespace(StoreNamespace("a"))
                assertEquals(1, calls)
                val postClearReader =
                    store.awaitLocalOnlyMissingReaderBarrier(key, backgroundScope)
                assertEquals(1, calls)
                awaitCurrentReaderFirstDelivery(key)

                val collector = store.stream(key).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(collector.awaitItem())
                refetchStarted.awaitFromDefault()
                // The new collector's initial Loading is sent before StreamDelivery.start installs
                // its ticket watcher. Drain that continuation while fetch 2 remains gated so its
                // post-clear demand is enrolled before the shared outcome can settle.
                runCurrent()
                assertEquals(2, calls)
                releaseRefetch.complete(Unit)
                val fresh = collector.awaitFreshDataAfterClear(forbidden = "v1")
                assertEquals("v2", fresh.value)
                assertFalse(fresh.isStale)
                assertFalse(fresh.refreshing)
                collector.expectNoEvents()
                val localFresh = assertIs<StoreResult.Data<String>>(postClearReader.receive())
                assertEquals("v2", localFresh.value)
                assertFalse(localFresh.isStale)
                assertFalse(localFresh.refreshing)
                postClearReader.cancel()
                collector.cancelAndIgnoreRemainingEvents()
            }
            assertEquals(2, calls)
        } finally {
            releaseRefetch.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun clearAll_dropsResidenceForEveryKey() = runTest {
        var calls = 0
        val store = testStore<NamespacedTestKey, String> { fetcher { "v${++calls}" } }

        try {
            assertEquals("v1", store.get(NamespacedTestKey("a", "1")))
            assertEquals("v2", store.get(NamespacedTestKey("b", "2")))

            store.clearAll()

            // Residence is gone: both keys refetch.
            assertEquals("v3", store.get(NamespacedTestKey("a", "1")))
            assertEquals("v4", store.get(NamespacedTestKey("b", "2")))
        } finally {
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun maintenanceAfterClose_failsFastWithDeterministicException() = runTest {
        val store = testStore<TestKey, String> { fetcher { "v" } }
        try {
            store.close()

            assertEquals(
                "Store is closed.",
                assertFailsWith<IllegalStateException> { store.invalidate(TestKey("1")) }.message,
            )
            assertEquals(
                "Store is closed.",
                assertFailsWith<IllegalStateException> { store.clearAll() }.message,
            )
        } finally {
            store.closeAndSettleForTest()
        }
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<StoreResult<String>>.awaitDataValue(
        expected: String,
    ) {
        while (true) {
            when (val item = awaitItem()) {
                is StoreResult.Data -> {
                    assertEquals(expected, item.value, "pre-clear Data must never replay")
                    return
                }
                is StoreResult.Loading -> Unit
                is StoreResult.Error -> {
                    val cause = (item.error as? StoreError.Fetch)?.cause
                    throw AssertionError(
                        "unexpected clear-cycle error: ${item.error}; cause=${cause?.message}",
                        cause,
                    )
                }
                is StoreResult.Revalidated -> throw AssertionError("clear must not revalidate")
            }
        }
    }

    private suspend fun app.cash.turbine.ReceiveTurbine<StoreResult<String>>.awaitFreshDataAfterClear(
        forbidden: String,
    ): StoreResult.Data<String> {
        while (true) {
            when (val item = awaitItem()) {
                is StoreResult.Data -> {
                    assertTrue(item.value != forbidden, "pre-clear Data must never replay")
                    if (!item.isStale && !item.refreshing) return item
                }
                is StoreResult.Loading -> Unit
                is StoreResult.Error -> {
                    val cause = (item.error as? StoreError.Fetch)?.cause
                    throw AssertionError(
                        "unexpected clear-cycle error: ${item.error}; cause=${cause?.message}",
                        cause,
                    )
                }
                is StoreResult.Revalidated -> throw AssertionError("clear must not revalidate")
            }
        }
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

private suspend fun <K : StoreKey> Store<K, String>.awaitLocalOnlyMissingReaderBarrier(
    key: K,
    scope: kotlinx.coroutines.CoroutineScope,
): ReceiveChannel<StoreResult<String>> {
    val collector = stream(key, Freshness.LocalOnly).produceIn(scope)
    val missing = assertIs<StoreResult.Error>(collector.receive())
    assertIs<StoreError.Missing>(missing.error)
    assertFalse(missing.servedStale)
    return collector
}

// Preserve the cross-scheduler hop; runTest owns the timeout so broad-graph load cannot
// expire a shorter wall-clock deadline before the causal event reaches Dispatchers.Default.
private suspend fun <T> CompletableDeferred<T>.awaitFromDefault(): T =
    withContext(Dispatchers.Default) {
        await()
    }
