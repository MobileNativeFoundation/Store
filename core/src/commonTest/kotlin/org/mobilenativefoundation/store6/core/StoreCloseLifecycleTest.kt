package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import org.mobilenativefoundation.store6.core.internal.RealStore
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StoreCloseLifecycleTest {
    @Test
    fun close_cancelsSeededLocalOnlyGetWhileStatusRemainsSuspended() = runTest {
        val key = TestKey("close-status")
        val statusEntered = CompletableDeferred<Unit>()
        val statusGate = CompletableDeferred<Unit>()
        val statusExited = CompletableDeferred<Unit>()
        var suspendStatus = false
        val durable = InMemoryBookkeeper()
        val bookkeeping = object : Bookkeeper by durable {
            override suspend fun status(key: StoreKey): KeyStatus? {
                if (suspendStatus) {
                    statusEntered.complete(Unit)
                    try {
                        statusGate.await()
                    } finally {
                        statusExited.complete(Unit)
                    }
                }
                return durable.status(key)
            }
        }
        val persistence = InMemorySourceOfTruth<TestKey, String>()
        persistence.write(key, "seeded")
        val store = store<TestKey, String> {
            fetcher { error("LocalOnly must not fetch") }
            persistence(persistence)
            bookkeeper(bookkeeping)
        }
        assertEquals("seeded", store.get(key, Freshness.LocalOnly))
        suspendStatus = true
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            val result = runCatching { store.get(key, Freshness.LocalOnly) }
            assertTrue(currentCoroutineContext().isActive, "Store close must preserve the caller job")
            result
        }
        try {
            statusEntered.await()
            store.close()
            runCurrent()
            assertFalse(statusGate.isCompleted)
            assertTrue(statusExited.isCompleted, "The suspended status call must be cancelled")
            assertTrue(request.isCompleted, "Get must settle without opening the status gate")
            val failure = assertIs<CancellationException>(request.await().exceptionOrNull())
            assertEquals("Store is closed.", failure.message)
        } finally {
            statusGate.complete(Unit)
            request.cancelAndJoin()
            store.close()
        }
    }

    @Test
    fun close_cancelsInitialHydrationAndQueuedHydrationWithoutOpeningReaderGate() = runTest {
        val key = TestKey("close-hydration")
        val readerEntered = CompletableDeferred<Unit>()
        val readerGate = CompletableDeferred<Unit>()
        val readerExited = CompletableDeferred<Unit>()
        val durable = InMemorySourceOfTruth<TestKey, String>()
        durable.write(key, "seeded")
        var readerCalls = 0
        val persistence = object : SourceOfTruth<TestKey, String> by durable {
            override fun reader(key: TestKey) = flow {
                readerCalls += 1
                readerEntered.complete(Unit)
                try {
                    readerGate.await()
                    emitAll(durable.reader(key))
                } finally {
                    readerExited.complete(Unit)
                }
            }
        }
        val store = store<TestKey, String> {
            fetcher { error("LocalOnly must not fetch") }
            persistence(persistence)
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            val result = runCatching { store.get(key, Freshness.LocalOnly) }
            assertTrue(currentCoroutineContext().isActive)
            result
        }
        readerEntered.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            val result = runCatching { store.get(key, Freshness.LocalOnly) }
            assertTrue(currentCoroutineContext().isActive)
            result
        }
        try {
            assertEquals(1, readerCalls)
            store.close()
            runCurrent()
            assertFalse(readerGate.isCompleted)
            assertTrue(readerExited.isCompleted, "First hydration must release its reader")
            assertTrue(first.isCompleted, "First hydration must settle on close")
            assertTrue(queued.isCompleted, "Queued hydration must leave lock admission on close")
            for (request in listOf(first, queued)) {
                val failure = assertIs<CancellationException>(request.await().exceptionOrNull())
                assertEquals("Store is closed.", failure.message)
            }
            assertEquals(1, readerCalls)
        } finally {
            readerGate.complete(Unit)
            first.cancelAndJoin()
            queued.cancelAndJoin()
            store.close()
        }
    }

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
