package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.internal.RealStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class StoreCloseLifecycleTest {
    @Test
    fun close_cancelsCollectorsAndFetches_releasesRegistry_leakChecked() =
        runTest(timeout = 60.seconds) {
            val key = TestKey("close-active-work")
            val fetchGate = CompletableDeferred<Unit>()
            val fetchStarted = CompletableDeferred<Unit>()
            val firstFrame = CompletableDeferred<Unit>()
            val store =
                store<TestKey, String> {
                    fetcher {
                        fetchStarted.complete(Unit)
                        fetchGate.await()
                        "value"
                    }
                } as RealStore<TestKey, String>
            val waiter =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { store.get(key, Freshness.MustBeFresh) }
                }
            var collector: Deferred<Result<Unit>>? = null

            try {
                fetchStarted.await()
                val activeCollector =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching {
                            store.stream(key).collect {
                                firstFrame.complete(Unit)
                            }
                        }
                    }
                collector = activeCollector
                firstFrame.await()

                store.close()

                val collectorFailure =
                    assertIs<CancellationException>(activeCollector.await().exceptionOrNull())
                assertEquals("Store is closed.", collectorFailure.message)
                val waiterFailure =
                    assertIs<CancellationException>(waiter.await().exceptionOrNull())
                assertEquals("Store is closed.", waiterFailure.message)
                store.awaitTerminationForTest()
                assertEquals(0, store.residentEngineCountForTest())
            } finally {
                withContext(NonCancellable) {
                    fetchGate.complete(Unit)
                    waiter.cancelAndJoin()
                    collector?.cancelAndJoin()
                    store.close()
                }
            }
        }

    @Test
    fun postClose_everyOperationFailsFast_withExactMessage() = runTest(timeout = 60.seconds) {
        val key = TestKey("post-close")
        val namespace = StoreNamespace("test")
        val store = store<TestKey, String> { fetcher { "value" } }
        val preCloseStream = store.stream(key)

        store.close()
        store.close()

        assertStoreClosed { store.get(key) }
        assertStoreClosed { store.stream(key) }
        assertStoreClosed { preCloseStream.collect() }
        assertStoreClosed { store.invalidate(key) }
        assertStoreClosed { store.invalidateNamespace(namespace) }
        assertStoreClosed { store.invalidateAll() }
        assertStoreClosed { store.clear(key) }
        assertStoreClosed { store.clearNamespace(namespace) }
        assertStoreClosed { store.clearAll() }
    }

    @Test
    fun repeatedOpenCloseCycles_leaveNoResidentState() = runTest(timeout = 60.seconds) {
        repeat(50) { cycle ->
            val key = TestKey("cycle-$cycle")
            val store =
                store<TestKey, String> {
                    fetcher { "value-$cycle" }
                } as RealStore<TestKey, String>

            try {
                assertEquals("value-$cycle", store.get(key))
            } finally {
                store.close()
                store.awaitTerminationForTest()
            }
            assertEquals(0, store.residentEngineCountForTest())
        }
    }

    private suspend fun assertStoreClosed(operation: suspend () -> Unit) {
        val failure = assertFailsWith<IllegalStateException> { operation() }
        assertEquals("Store is closed.", failure.message)
    }
}
