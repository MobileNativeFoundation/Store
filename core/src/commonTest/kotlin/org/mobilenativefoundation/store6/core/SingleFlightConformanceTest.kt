package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

open class SingleFlightConformanceTest : SourceOfTruthSubstitutionTest() {
    @Test
    fun ac2_fiftyGettersAndFiftyCollectorsShareOneFetch() = runTest {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcher {
                calls++
                started.complete(Unit)
                gate.await()
                "v"
            }
        }
        val key = TestKey("1")

        try {
            val getters =
                List(50) {
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        store.get(key)
                    }
                }
            val collectorRegistered = List(50) { CompletableDeferred<Unit>() }
            val collectors =
                List(50) { index ->
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        val item =
                            store
                                .stream(key)
                                .onEach { result ->
                                    if (result is StoreResult.Loading) {
                                        collectorRegistered[index].complete(Unit)
                                    }
                                }
                                .first { it is StoreResult.Data<*> }
                        assertIs<StoreResult.Data<String>>(item).value
                    }
                }

            started.await()
            // A channelFlow producer may start after the fetcher's signal. Loading proves each
            // stream demand joined the still-gated ticket before the fetch is allowed to settle.
            collectorRegistered.awaitAll()
            assertEquals(1, calls)
            gate.complete(Unit)

            assertTrue(getters.awaitAll().all { it == "v" })
            assertTrue(collectors.awaitAll().all { it == "v" })
            assertEquals(1, calls)
        } finally {
            store.close()
        }
    }

    @Test
    fun cancelledWaiterDoesNotCancelSharedFetch() = runTest {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcher {
                calls++
                started.complete(Unit)
                gate.await()
                "v"
            }
        }
        val key = TestKey("1")

        try {
            val cancelled =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.get(key)
                }
            started.await()
            cancelled.cancelAndJoin()
            assertTrue(cancelled.isCancelled)

            val later =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.get(key)
                }
            gate.complete(Unit)

            assertEquals("v", later.await())
            assertEquals("v", store.get(key))
            assertEquals(1, calls)
        } finally {
            store.close()
        }
    }
}
