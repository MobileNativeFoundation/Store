package org.mobilenativefoundation.store6.core

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import org.mobilenativefoundation.store6.core.internal.RealStore
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalStoreApi::class)
class StoreEvictionConformanceTest {
    @Test
    fun evictedEngine_recreation_semanticallyInvisible() = runTest(timeout = 60.seconds) {
        val key = TestKey("A")
        val clock = FakeWallClock(now = 1_000L)
        val secondFetchGate = CompletableDeferred<Unit>()
        val thirdFetchGate = CompletableDeferred<Unit>()
        var aFetches = 0
        val store =
            storeWith<TestKey, String>(clock = clock) {
                maxIdleKeys(4)
                fetcher { requested ->
                    if (requested.canonicalId() != "A") {
                        "${requested.canonicalId()}-value"
                    } else {
                        when (++aFetches) {
                            2 -> secondFetchGate.await()
                            3 -> thirdFetchGate.await()
                        }
                        "A-v$aFetches"
                    }
                }
            } as RealStore<TestKey, String>

        try {
            assertEquals("A-v1", store.get(key))
            clock.now += 1_000L
            store.invalidate(key)

            val destroyedBeforeFirstChurn = store.destroyedEngineCountForTest()
            repeat(9) { index -> store.get(TestKey("first-churn-$index")) }
            awaitUntil { store.destroyedEngineCountForTest() > destroyedBeforeFirstChurn }
            val createdBeforeFirstRecreation = store.createdEngineCountForTest()

            store.stream(key).test {
                val stale = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("A-v1", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)
                assertEquals(
                    createdBeforeFirstRecreation + 1L,
                    store.createdEngineCountForTest(),
                    "A must be recreated after leaving the idle LRU",
                )

                secondFetchGate.complete(Unit)
                val fresh = awaitData("A-v2")
                assertFalse(fresh.isStale)
                assertFalse(fresh.refreshing)
                cancelAndIgnoreRemainingEvents()
            }

            clock.now += 1_000L
            store.invalidateNamespace(StoreNamespace("test"))
            val destroyedBeforeSecondChurn = store.destroyedEngineCountForTest()
            repeat(9) { index -> store.get(TestKey("second-churn-$index")) }
            awaitUntil { store.destroyedEngineCountForTest() > destroyedBeforeSecondChurn }
            val createdBeforeSecondRecreation = store.createdEngineCountForTest()

            store.stream(key).test {
                val stale = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("A-v2", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)
                assertEquals(
                    createdBeforeSecondRecreation + 1L,
                    store.createdEngineCountForTest(),
                    "the namespace-invalidated A engine must be recreated",
                )
                thirdFetchGate.complete(Unit)
                awaitData("A-v3")
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            secondFetchGate.complete(Unit)
            thirdFetchGate.complete(Unit)
            store.close()
        }
    }

    @Test
    fun memoryCache_neverDivergesFromDurableTruth() = runTest(timeout = 60.seconds) {
        val key = TestKey("truth")
        val sourceOfTruth =
            CountingSourceOfTruth(InMemorySourceOfTruth<TestKey, String>())
        var fetches = 0
        val store =
            store<TestKey, String> {
                persistence(sourceOfTruth)
                fetcher { "fetched-${++fetches}" }
            }

        try {
            assertEquals("fetched-1", store.get(key))
            assertDurableRowMatchesResidence(sourceOfTruth, store, key)

            store.invalidate(key)
            assertDurableRowMatchesResidence(sourceOfTruth, store, key)

            assertEquals("fetched-2", store.get(key, Freshness.MustBeFresh))
            assertDurableRowMatchesResidence(sourceOfTruth, store, key)

            store.clear(key)
            assertDurableRowMatchesResidence(sourceOfTruth, store, key)

            store.stream(key, Freshness.LocalOnly).test {
                val missing = assertIs<StoreResult.Error>(awaitItem())
                assertIs<StoreError.Missing>(missing.error)
                sourceOfTruth.write(key, "external")
                assertEquals("external", awaitData("external").value)
                assertDurableRowMatchesResidence(sourceOfTruth, store, key)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun quiescentKeys_parkInIdle_boundedByMaxIdleKeys() = runTest(timeout = 60.seconds) {
        val store =
            store<TestKey, String> {
                maxIdleKeys(8)
                fetcher { "value-${it.canonicalId()}" }
            } as RealStore<TestKey, String>

        try {
            repeat(64) { index -> store.get(TestKey("idle-$index")) }
            awaitUntil {
                store.residentEngineCountForTest() == 8 &&
                    store.idleEngineCountForTest() == 8
            }

            assertEquals(8, store.residentEngineCountForTest())
            assertEquals(8, store.idleEngineCountForTest())
            assertEquals(64L, store.createdEngineCountForTest())
            assertEquals(56L, store.destroyedEngineCountForTest())
        } finally {
            store.close()
        }
    }

    @Test
    fun activeCollector_pinsEngine_acrossChurn() = runTest(timeout = 60.seconds) {
        val key = TestKey("A")
        val firstData = CompletableDeferred<Unit>()
        val refreshedData = CompletableDeferred<Unit>()
        val collectorOutcome = CompletableDeferred<Throwable?>()
        var aFetches = 0
        val store =
            store<TestKey, String> {
                maxIdleKeys(2)
                fetcher { requested ->
                    if (requested.canonicalId() == "A") {
                        "A-v${++aFetches}"
                    } else {
                        "${requested.canonicalId()}-value"
                    }
                }
            } as RealStore<TestKey, String>
        val collector =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                val outcome =
                    runCatching {
                        store.stream(key).collect { result ->
                            when (result) {
                                is StoreResult.Data -> {
                                    if (result.value == "A-v1") firstData.complete(Unit)
                                    if (result.value == "A-v2" && !result.isStale) {
                                        refreshedData.complete(Unit)
                                    }
                                }
                                is StoreResult.Error ->
                                    throw AssertionError("unexpected Store error: ${result.error}")
                                is StoreResult.Loading,
                                is StoreResult.Revalidated,
                                -> Unit
                            }
                        }
                    }
                collectorOutcome.complete(outcome.exceptionOrNull())
            }

        try {
            firstData.await()
            val destroyedBeforeChurn = store.destroyedEngineCountForTest()
            repeat(100) { index -> store.get(TestKey("active-churn-$index")) }
            awaitUntil {
                val resident = store.residentEngineCountForTest()
                store.destroyedEngineCountForTest() > destroyedBeforeChurn &&
                    resident == 3 &&
                    store.idleEngineCountForTest() == 2 &&
                    store.createdEngineCountForTest() - store.destroyedEngineCountForTest() ==
                    3L
            }

            assertTrue(collector.isActive)
            assertFalse(collectorOutcome.isCompleted)
            assertEquals(3, store.residentEngineCountForTest())
            assertEquals(2, store.idleEngineCountForTest())
            assertEquals(
                3L,
                store.createdEngineCountForTest() - store.destroyedEngineCountForTest(),
            )

            store.invalidate(key)
            refreshedData.await()
            assertEquals(2, aFetches)
            assertTrue(collector.isActive, "churn must not close-cancel a held engine")
            assertFalse(collectorOutcome.isCompleted)
        } finally {
            collector.cancelAndJoin()
            store.close()
        }
    }

    @Test
    fun inFlightFetch_pinsEngine_acrossWaiterCancellation_andCommits() =
        runTest(timeout = 60.seconds) {
            val key = TestKey("A")
            val fetchStarted = CompletableDeferred<Unit>()
            val fetchGate = CompletableDeferred<Unit>()
            val sourceOfTruth = InMemorySourceOfTruth<TestKey, String>()
            var aFetches = 0
            val store =
                store<TestKey, String> {
                    maxIdleKeys(2)
                    persistence(sourceOfTruth)
                    fetcher { requested ->
                        if (requested.canonicalId() == "A") {
                            aFetches += 1
                            fetchStarted.complete(Unit)
                            fetchGate.await()
                            "A-v$aFetches"
                        } else {
                            "${requested.canonicalId()}-value"
                        }
                    }
                } as RealStore<TestKey, String>
            val waiter =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.get(key)
                }

            try {
                fetchStarted.await()
                waiter.cancelAndJoin()
                repeat(40) { index -> store.get(TestKey("fetch-churn-$index")) }
                awaitUntil {
                    val resident = store.residentEngineCountForTest()
                    resident == 3 &&
                        store.idleEngineCountForTest() == 2 &&
                        store.createdEngineCountForTest() - store.destroyedEngineCountForTest() ==
                        3L
                }
                assertEquals(3, store.residentEngineCountForTest())
                assertEquals(2, store.idleEngineCountForTest())
                assertEquals(
                    3L,
                    store.createdEngineCountForTest() - store.destroyedEngineCountForTest(),
                )

                fetchGate.complete(Unit)
                awaitUntil {
                    sourceOfTruth.reader(key).first() == "A-v1" &&
                        store.createdEngineCountForTest() - store.destroyedEngineCountForTest() ==
                        store.residentEngineCountForTest().toLong() &&
                        store.idleEngineCountForTest() == store.residentEngineCountForTest()
                }

                assertEquals(1, aFetches)
                assertEquals("A-v1", store.get(key))
                assertEquals(1, aFetches, "the committed value must be reused without refetch")
            } finally {
                fetchGate.complete(Unit)
                waiter.cancelAndJoin()
                store.close()
            }
        }

    private suspend fun ReceiveTurbine<StoreResult<String>>.awaitData(
        expected: String,
    ): StoreResult.Data<String> {
        while (true) {
            when (val result = awaitItem()) {
                is StoreResult.Data -> if (result.value == expected) return result
                is StoreResult.Error -> throw AssertionError("unexpected Store error: ${result.error}")
                is StoreResult.Loading,
                is StoreResult.Revalidated,
                -> Unit
            }
        }
    }

    private suspend fun assertDurableRowMatchesResidence(
        sourceOfTruth: CountingSourceOfTruth<TestKey, String>,
        store: Store<TestKey, String>,
        key: TestKey,
    ) {
        val durableRow = sourceOfTruth.peek(key)
        val readerCallsBeforeObservation = sourceOfTruth.readerCalls
        if (durableRow == null) {
            val failure =
                assertFailsWith<StoreException> {
                    store.get(key, Freshness.LocalOnly)
                }
            assertIs<StoreError.Missing>(failure.error)
            // With no resident envelope, LocalOnly must consult SoT to establish typed Missing.
            // Reading absence cannot repair a divergence because there is no durable row to copy.
            assertEquals(
                readerCallsBeforeObservation + 1,
                sourceOfTruth.readerCalls,
                "absent residence must be confirmed by exactly one SourceOfTruth reader",
            )
        } else {
            assertEquals(durableRow, store.get(key, Freshness.LocalOnly))
            assertEquals(
                readerCallsBeforeObservation,
                sourceOfTruth.readerCalls,
                "LocalOnly must not hydrate and mask a lost non-null residence",
            )
        }
    }

    @OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
    private class CountingSourceOfTruth<K : StoreKey, V : Any>(
        private val delegate: SourceOfTruth<K, V>,
    ) : SourceOfTruth<K, V> {
        private val readerCallState = MutableStateFlow(0)

        val readerCalls: Int
            get() = readerCallState.value

        override fun reader(key: K): Flow<V?> {
            readerCallState.update { count -> count + 1 }
            return delegate.reader(key)
        }

        override suspend fun write(
            key: K,
            value: V,
        ) {
            delegate.write(key, value)
        }

        override suspend fun delete(key: K) {
            delegate.delete(key)
        }

        override suspend fun deleteNamespace(namespace: StoreNamespace) {
            delegate.deleteNamespace(namespace)
        }

        override suspend fun deleteAll() {
            delegate.deleteAll()
        }

        suspend fun peek(key: K): V? = delegate.reader(key).first()
    }
}
