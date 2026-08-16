package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.internal.RealStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalStoreApi::class)
class StoreEvictionStressTest {
    @Test
    fun churn10kKeyCycles_neverEvictsHeldEngines_andResidencyStaysBounded() =
        runTest(timeout = 120.seconds) {
            val allWorkersHolding = CompletableDeferred<Unit>()
            val holdingWorkersLock = Mutex()
            var holdingWorkers = 0
            val keys = List(KEY_SPACE) { index -> TestKey("stress-$index") }
            val store =
                store<TestKey, String> {
                    maxIdleKeys(16)
                    fetcher { "value" }
                } as RealStore<TestKey, String>

            try {
                withContext(Dispatchers.Default) {
                    coroutineScope {
                        repeat(WORKERS) { worker ->
                            launch {
                                val key = keys[worker]
                                store.withEngine(key) {
                                    holdingWorkersLock.withLock {
                                        holdingWorkers += 1
                                        if (holdingWorkers == WORKERS) {
                                            allWorkersHolding.complete(Unit)
                                        }
                                    }
                                    allWorkersHolding.await()
                                    repeat(STEPS_PER_WORKER) { operation ->
                                        when (operation % 4) {
                                            0 -> store.get(key)
                                            1 -> store.stream(key).awaitFirstDataOrThrow()
                                            2 -> {
                                                store.invalidate(key)
                                                // Settle refresh before clear; CachedOrFetch would
                                                // deliberately race its background refresh.
                                                store.get(key, Freshness.MustBeFresh)
                                            }
                                            3 -> store.clear(key)
                                            else -> error("unreachable operation")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                assertTrue(WORKERS * STEPS_PER_WORKER >= 10_000)
                awaitUntil {
                    val resident = store.residentEngineCountForTest()
                    store.createdEngineCountForTest() == WORKERS.toLong() &&
                        store.destroyedEngineCountForTest() == 16L &&
                        resident == 16 &&
                        store.idleEngineCountForTest() == 16 &&
                        store.createdEngineCountForTest() - store.destroyedEngineCountForTest() ==
                        16L
                }
                assertEquals(WORKERS.toLong(), store.createdEngineCountForTest())
                assertEquals(16L, store.destroyedEngineCountForTest())
                assertEquals(16, store.residentEngineCountForTest())
                assertEquals(16, store.idleEngineCountForTest())
                assertEquals(
                    16L,
                    store.createdEngineCountForTest() - store.destroyedEngineCountForTest(),
                )

                store.close()
                store.awaitTerminationForTest()
                assertEquals(0, store.residentEngineCountForTest())
            } finally {
                store.close()
            }
        }

    private suspend fun Flow<StoreResult<String>>.awaitFirstDataOrThrow() {
        first { result ->
            when (result) {
                is StoreResult.Data -> true
                is StoreResult.Error ->
                    throw AssertionError("unexpected Store error: ${result.error}")
                is StoreResult.Loading,
                is StoreResult.Revalidated,
                -> false
            }
        }
    }

    private companion object {
        const val WORKERS = 32
        const val STEPS_PER_WORKER = 314
        // Twice the idle cap keeps every worker pinned above the eviction threshold.
        const val KEY_SPACE = WORKERS
    }
}
