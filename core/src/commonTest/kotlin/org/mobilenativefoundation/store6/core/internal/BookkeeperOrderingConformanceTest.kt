package org.mobilenativefoundation.store6.core.internal

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.FakeWallClock
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.TestKey
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.SingleRowTestSourceOfTruth
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class)
class BookkeeperOrderingConformanceTest {
    private val key = TestKey("1")
    private val keyId = KeyId.from(key)

    @Test
    fun clearCannotBeOvertakenByAnOlderSuccessfulCommit() = runTest {
        val successGate = MutationGate()
        val bookkeeper = GateableBookkeeper(successGate = successGate)
        val engine = engine(bookkeeper) { FetcherResult.Success("v1", etag = "e1") }

        val read =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.get(Freshness.CachedOrFetch) }
            }
        testScheduler.runCurrent()
        successGate.entered.await()

        val clear =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.clear()
            }
        testScheduler.runCurrent()

        successGate.release()
        testScheduler.runCurrent()
        read.await()
        clear.await()

        assertNull(bookkeeper.status(key))
    }

    @Test
    fun confirmFresh_successTailBlocksSameKeyClearUntilBookkeepingCompletes() = runTest {
        val bookkeeper = OrderedConfirmFreshBookkeeper()
        val engine = engine(bookkeeper) { FetcherResult.Success("v1", etag = "e1") }

        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        assertEquals(1, bookkeeper.successCalls)
        assertEquals(listOf("success1:start", "success1:end"), bookkeeper.events)

        val confirmation =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.confirmFresh(etag = "confirmed")
            }
        testScheduler.runCurrent()

        var clearPassedWhileSuccessBlocked = false
        var eventsWhileSuccessBlocked: List<String> = emptyList()
        val clear =
            try {
                withTimeout(1_000L) { bookkeeper.secondSuccessStarted.await() }
                assertEquals(2, bookkeeper.successCalls)
                assertEquals(0, bookkeeper.forgetCalls)

                val pending =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        engine.clear()
                    }
                testScheduler.runCurrent()
                clearPassedWhileSuccessBlocked = pending.isCompleted
                eventsWhileSuccessBlocked = bookkeeper.events.toList()
                pending
            } finally {
                bookkeeper.releaseSecondSuccess.complete(Unit)
            }

        confirmation.await()
        clear.await()

        assertFalse(
            clearPassedWhileSuccessBlocked,
            "same-key clear passed the blocked confirmFresh tail: $eventsWhileSuccessBlocked",
        )
        assertEquals(
            listOf("success1:start", "success1:end", "success2:start"),
            eventsWhileSuccessBlocked,
        )
        assertEquals(
            listOf(
                "success1:start",
                "success1:end",
                "success2:start",
                "success2:end",
                "forget:start",
                "forget:end",
            ),
            bookkeeper.events,
        )
        assertEquals(2, bookkeeper.successCalls)
        assertEquals(1, bookkeeper.forgetCalls)
        assertNull(bookkeeper.status(key))
        val missing =
            assertFailsWith<StoreException> {
                engine.get(Freshness.LocalOnly)
            }
        assertIs<StoreError.Missing>(missing.error)
    }

    @Test
    fun serverDeleteCannotEraseANewerSuccessfulCommit() = runTest {
        val forgetGate = MutationGate()
        val bookkeeper = GateableBookkeeper(forgetGate = forgetGate)
        var calls = 0
        val engine =
            engine(bookkeeper) {
                when (++calls) {
                    1 -> FetcherResult.Success("v1", etag = "e1")
                    2 -> FetcherResult.Deleted
                    3 -> FetcherResult.Success("v2", etag = "e2")
                    else -> error("unexpected fetch call $calls")
                }
            }

        val seed =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.get(Freshness.CachedOrFetch)
            }
        testScheduler.runCurrent()
        assertEquals("v1", seed.await())

        val deletion =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.get(Freshness.MustBeFresh) }
            }
        testScheduler.runCurrent()
        forgetGate.entered.await()

        val replacement =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.get(Freshness.CachedOrFetch)
            }
        testScheduler.runCurrent()

        forgetGate.release()
        testScheduler.runCurrent()
        deletion.await()
        assertEquals("v2", replacement.await())
        assertEquals("e2", bookkeeper.status(key)?.meta?.etag)
    }

    @Test
    fun failureAfterClearCannotRecreateFailureStatus() = runTest {
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val bookkeeper = GateableBookkeeper()
        val engine =
            engine(bookkeeper) {
                fetchStarted.complete(Unit)
                releaseFetch.await()
                throw IllegalStateException("boom")
            }

        val read =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.get(Freshness.CachedOrFetch) }
            }
        testScheduler.runCurrent()
        fetchStarted.await()

        val clear =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.clear()
            }
        testScheduler.runCurrent()
        clear.await()

        releaseFetch.complete(Unit)
        testScheduler.runCurrent()
        read.await()

        assertNull(bookkeeper.status(key))
    }

    @Test
    fun failureTimestampIsCapturedBeforeOrderedBookkeepingWait() = runTest {
        val markGate = MutationGate()
        val failureFetched = CompletableDeferred<Unit>()
        val bookkeeper = GateableBookkeeper(markGate = markGate)
        val clock = FakeWallClock(now = 0L)
        val sot = InMemorySourceOfTruth<TestKey, String>()
        sot.write(key, "v1")
        var calls = 0
        val engine =
            engine(bookkeeper, clock, sot = sot) {
                calls += 1
                failureFetched.complete(Unit)
                FetcherResult.Error(IllegalStateException("boom"))
            }

        assertEquals("v1", engine.get(Freshness.LocalOnly))
        assertEquals(0, calls, "LocalOnly hydration must not fetch")

        val orderedInvalidation =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.invalidate()
            }
        testScheduler.runCurrent()
        markGate.entered.await()

        try {
            clock.now = 10L
            val failedRefresh =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { engine.get(Freshness.MustBeFresh) }
                }
            testScheduler.runCurrent()
            failureFetched.await()

            clock.now = 99L
            markGate.release()
            testScheduler.runCurrent()
            orderedInvalidation.await()
            failedRefresh.await()
        } finally {
            markGate.release()
        }

        assertEquals(10L, bookkeeper.status(key)?.lastFailureAtEpochMillis)
        assertEquals(1, calls)
    }

    @Test
    fun engineCancellationReleasesOrdinaryWriteFailureBookkeeping() = runTest {
        val failureGate = MutationGate()
        val bookkeeper = GateableBookkeeper(failureGate = failureGate)
        val sot = ThrowingWriteSourceOfTruth()
        val engineJob = Job(backgroundScope.coroutineContext[Job])
        val engineScope = CoroutineScope(backgroundScope.coroutineContext + engineJob)
        val engine =
            engine(bookkeeper, engineScope = engineScope, sot = sot) {
                FetcherResult.Success("v1", etag = "e1")
            }
        val read =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.get(Freshness.CachedOrFetch) }
            }
        testScheduler.runCurrent()
        sot.writeAttempted.await()
        failureGate.entered.await()

        try {
            engineJob.cancel()
            val clear =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    engine.clear()
                }
            testScheduler.runCurrent()

            assertTrue(clear.isCompleted, "engine cancellation must release writeLock")
            clear.await()
        } finally {
            failureGate.release()
            testScheduler.runCurrent()
        }
        read.await()
    }

    @Test
    fun engineCancellationReleasesBlockedFailureBookkeeping() = runTest {
        val failureGate = MutationGate()
        val bookkeeper = GateableBookkeeper(failureGate = failureGate)
        val engineJob = Job(backgroundScope.coroutineContext[Job])
        val engineScope = CoroutineScope(backgroundScope.coroutineContext + engineJob)
        val engine =
            engine(bookkeeper, engineScope = engineScope) {
                FetcherResult.Error(IllegalStateException("boom"))
            }
        val read =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.get(Freshness.CachedOrFetch) }
            }
        testScheduler.runCurrent()
        failureGate.entered.await()

        try {
            engineJob.cancel()
            val clear =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    engine.clear()
                }
            testScheduler.runCurrent()

            assertTrue(clear.isCompleted, "engine cancellation must release bookkeepingLock")
            clear.await()
        } finally {
            failureGate.release()
            testScheduler.runCurrent()
        }
        read.await()
    }

    @Test
    fun invalidate_marksBeforeEpochSignal_andBlocksSameKeyCommit() = runTest {
        val events = mutableListOf<String>()
        val markGate = MutationGate()
        val durableBookkeeper = GateableBookkeeper(markGate = markGate, events = events)
        val durableSot = InMemorySourceOfTruth<TestKey, String>()
        val secondFetchEntered = CompletableDeferred<Unit>()
        var calls = 0
        val engine =
            engine(durableBookkeeper, sot = durableSot) {
                val call = ++calls
                if (call == 2) secondFetchEntered.complete(Unit)
                FetcherResult.Success("v$call", etag = "e$call")
            }
        assertEquals("v1", engine.get(Freshness.MustBeFresh))
        val initialEpoch = engine.state.value.staleEpoch

        engine.stream(Freshness.CachedOrFetch).test {
            assertEquals("v1", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            val invalidation =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { engine.invalidate() }
            testScheduler.runCurrent()
            try {
                assertTrue(markGate.entered.isCompleted, "durable mark must begin before epoch signal")
                assertEquals(initialEpoch, engine.state.value.staleEpoch)
                expectNoEvents()

                val competingCommit =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        engine.get(Freshness.MustBeFresh)
                    }
                withTimeout(1_000) { secondFetchEntered.await() }
                assertEquals(2, calls, "network may finish while its durable commit is fenced")
                assertEquals(listOf("success"), events, "second durable commit must still be blocked")
                assertEquals("v1", durableSot.reader(key).first())
                expectNoEvents()

                markGate.release()
                withTimeout(1_000) { invalidation.await() }
                assertEquals(initialEpoch + 1L, engine.state.value.staleEpoch)
                assertEquals("v2", withTimeout(1_000) { competingCommit.await() })
                while (true) {
                    val item = awaitItem()
                    if (item is StoreResult.Data && item.value == "v2") break
                }
                assertEquals(listOf("success", "markStale", "success"), events)
                assertEquals(2, calls, "the ordered mark/epoch/success cycle must single-flight")
                cancelAndIgnoreRemainingEvents()
            } finally {
                markGate.release()
            }
        }
    }

    private fun TestScope.engine(
        bookkeeper: Bookkeeper,
        clock: FakeWallClock = FakeWallClock(now = 0L),
        engineScope: CoroutineScope = backgroundScope,
        sot: SourceOfTruth<TestKey, String> = InMemorySourceOfTruth(),
        fetcher: suspend (TestKey) -> FetcherResult<String>,
    ): KeyEngine<TestKey, String> =
        KeyEngine(
            key = key,
            keyId = keyId,
            fetcher = ResultFetcher(fetcher),
            sot = sot,
            bookkeeper = bookkeeper,
            validator = DefaultFreshnessValidator,
            wallClock = clock,
            engineScope = engineScope,
        )

    private class MutationGate {
        val entered = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()

        suspend fun pause() {
            entered.complete(Unit)
            released.await()
        }

        fun release() {
            released.complete(Unit)
        }
    }

    private class GateableBookkeeper(
        successGate: MutationGate? = null,
        private val failureGate: MutationGate? = null,
        private val forgetGate: MutationGate? = null,
        private val markGate: MutationGate? = null,
        private val events: MutableList<String>? = null,
    ) : Bookkeeper {
        private val delegate = InMemoryBookkeeper()
        private var successGate = successGate

        fun gateNextSuccess(gate: MutationGate) {
            successGate = gate
        }

        override suspend fun recordSuccess(
            key: StoreKey,
            meta: StoreMeta,
        ) {
            successGate?.also { successGate = null }?.pause()
            events?.add("success")
            delegate.recordSuccess(key, meta)
        }

        override suspend fun recordFailure(
            key: StoreKey,
            atEpochMillis: Long,
        ) {
            failureGate?.pause()
            delegate.recordFailure(key, atEpochMillis)
        }

        override suspend fun status(key: StoreKey): KeyStatus? = delegate.status(key)

        override suspend fun forget(key: StoreKey) {
            forgetGate?.pause()
            delegate.forget(key)
        }

        override suspend fun markStale(key: StoreKey) {
            markGate?.pause()
            events?.add("markStale")
            delegate.markStale(key)
        }

        override suspend fun advanceStaleWatermark(namespace: StoreNamespace) =
            delegate.advanceStaleWatermark(namespace)

        override suspend fun advanceGlobalStaleWatermark() =
            delegate.advanceGlobalStaleWatermark()

        override suspend fun forgetNamespace(namespace: StoreNamespace) =
            delegate.forgetNamespace(namespace)

        override suspend fun forgetAll() = delegate.forgetAll()
    }

    private class OrderedConfirmFreshBookkeeper : Bookkeeper {
        private val delegate = InMemoryBookkeeper()
        val secondSuccessStarted = CompletableDeferred<Unit>()
        val releaseSecondSuccess = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var successCalls = 0
            private set
        var forgetCalls = 0
            private set

        override suspend fun recordSuccess(
            key: StoreKey,
            meta: StoreMeta,
        ) {
            val call = ++successCalls
            events += "success$call:start"
            if (call == 2) {
                secondSuccessStarted.complete(Unit)
                releaseSecondSuccess.await()
            }
            delegate.recordSuccess(key, meta)
            events += "success$call:end"
        }

        override suspend fun recordFailure(
            key: StoreKey,
            atEpochMillis: Long,
        ) = delegate.recordFailure(key, atEpochMillis)

        override suspend fun status(key: StoreKey): KeyStatus? = delegate.status(key)

        override suspend fun forget(key: StoreKey) {
            forgetCalls += 1
            events += "forget:start"
            delegate.forget(key)
            events += "forget:end"
        }

        override suspend fun markStale(key: StoreKey) = delegate.markStale(key)

        override suspend fun advanceStaleWatermark(namespace: StoreNamespace) =
            delegate.advanceStaleWatermark(namespace)

        override suspend fun advanceGlobalStaleWatermark() =
            delegate.advanceGlobalStaleWatermark()

        override suspend fun forgetNamespace(namespace: StoreNamespace) =
            delegate.forgetNamespace(namespace)

        override suspend fun forgetAll() = delegate.forgetAll()
    }

    private class ThrowingWriteSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val value = MutableStateFlow<String?>(null)
        val writeAttempted = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> = value

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            writeAttempted.complete(Unit)
            throw IllegalStateException("write failed")
        }

        override suspend fun delete(key: TestKey) {
            value.value = null
        }
    }
}
