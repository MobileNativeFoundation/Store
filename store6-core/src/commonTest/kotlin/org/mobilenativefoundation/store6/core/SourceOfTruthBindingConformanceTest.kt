package org.mobilenativefoundation.store6.core

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mobilenativefoundation.store6.core.internal.DefaultFreshnessValidator
import org.mobilenativefoundation.store6.core.internal.FetchDisposition
import org.mobilenativefoundation.store6.core.internal.FetchOutcome
import org.mobilenativefoundation.store6.core.internal.FetchSlot
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.KeyEngine
import org.mobilenativefoundation.store6.core.internal.KeyId
import org.mobilenativefoundation.store6.core.internal.ResultFetcher
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)
class SourceOfTruthBindingConformanceTest {
    private val key = TestKey("key")

    @Test
    fun hydrationQueuedBeforeClear_cannotResurrectAfterClearCompletes() = runTest {
        val sourceOfTruth = GatedHydrationSourceOfTruth()
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { error("fetch must not run") }
        }

        try {
            val hydration =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.get(key, Freshness.LocalOnly)
                }
            sourceOfTruth.readerStarted.await()
            val clear =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.clear(key)
                }
            runCurrent()

            sourceOfTruth.releaseReader.complete(Unit)
            assertEquals("stale", hydration.await())
            clear.await()

            val missing =
                assertFailsWith<StoreException> {
                    store.get(key, Freshness.LocalOnly)
                }
            assertIs<StoreError.Missing>(missing.error)
        } finally {
            sourceOfTruth.releaseReader.complete(Unit)
            store.close()
        }
    }

    @Test
    fun externalReplacementAfterWriteReturn_winsDuringGatedRecordSuccess() = runTest {
        val sourceOfTruth = MutableSourceOfTruth(initial = "seed")
        val bookkeeper = GateNextSuccessBookkeeper()
        val secondFetchStarted = CompletableDeferred<Unit>()
        val releaseSecondFetch = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val store = storeWith<TestKey, String>(bookkeeper = bookkeeper) {
            persistence(sourceOfTruth)
            fetcher {
                when (val call = ++fetchCalls) {
                    1 -> "fetched-1"
                    2 -> {
                        secondFetchStarted.complete(Unit)
                        releaseSecondFetch.await()
                        "fetched-2"
                    }

                    else -> error("unexpected fetch call $call")
                }
            }
        }

        try {
            assertEquals("seed", store.get(key, Freshness.LocalOnly))
            bookkeeper.gateNextSuccess()
            app.cash.turbine.turbineScope {
                val collector = store.stream(key).testIn(backgroundScope)
                withContext(Dispatchers.Default) {
                    bookkeeper.successEntered.await()
                }

                sourceOfTruth.publishExternal("external")
                awaitLocalValue(store, "external")
                bookkeeper.releaseSuccess.complete(Unit)
                withContext(Dispatchers.Default) {
                    secondFetchStarted.await()
                }
                assertEquals("external", store.get(key, Freshness.LocalOnly))

                releaseSecondFetch.complete(Unit)
                awaitLocalValue(store, "fetched-2")
                assertEquals(2, fetchCalls)
                collector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            bookkeeper.releaseSuccess.complete(Unit)
            releaseSecondFetch.complete(Unit)
            store.close()
        }
    }

    @Test
    fun notModified_externalReplacementObservedAfterLaunch_isObsoleteNotFreshened() = runTest {
        val sourceOfTruth = MutableSourceOfTruth()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        var calls = 0
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcherOfResult {
                when (++calls) {
                    1 -> FetcherResult.Success("v1", etag = "e1")
                    2 -> {
                        secondStarted.complete(Unit)
                        releaseSecond.await()
                        FetcherResult.NotModified(etag = "e2")
                    }

                    3 -> FetcherResult.Success("fresh", etag = "e3")
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(key))
            app.cash.turbine.turbineScope {
                val observer = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("v1", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
                val fresh =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        store.get(key, Freshness.MustBeFresh)
                    }
                secondStarted.await()

                sourceOfTruth.publishExternal("external")
                var external: StoreResult.Data<String>? = null
                while (true) {
                    val item = observer.awaitItem()
                    if (item is StoreResult.Data && item.value == "external") {
                        external = item
                        break
                    }
                }
                assertExternalSotStale(assertNotNull(external))
                releaseSecond.complete(Unit)

                assertEquals("fresh", fresh.await())
                var subsequentSuccess: StoreResult.Data<String>? = null
                while (subsequentSuccess == null) {
                    val item = observer.awaitItem()
                    if (item is StoreResult.Data) {
                        when (item.value) {
                            "external" -> assertExternalSotStale(item)
                            "fresh" -> subsequentSuccess = item
                            else -> error("unexpected value after external replacement: ${item.value}")
                        }
                    }
                }
                val success = assertNotNull(subsequentSuccess)
                assertEquals(Origin.FETCHER, success.origin)
                assertFalse(success.isStale)
                assertEquals(3, calls)
                observer.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseSecond.complete(Unit)
            store.close()
        }
    }

    @Test
    fun notModifiedDirectDelivery_revisionMismatchDoesNotEmitReplayedResidence() = runTest {
        val sourceOfTruth = ReplayableSourceOfTruth()
        val bookkeeper = GateNextSuccessBookkeeper()
        var calls = 0
        val store = storeWith<TestKey, String>(bookkeeper = bookkeeper) {
            persistence(sourceOfTruth)
            fetcherOfResult {
                when (++calls) {
                    1 -> FetcherResult.Success("v1", etag = "e1")
                    2 -> FetcherResult.NotModified(etag = "e2")
                    3 -> FetcherResult.Success("fresh", etag = "e3")
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(key))
            app.cash.turbine.turbineScope {
                val observer = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("v1", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
                sourceOfTruth.liveReaderStarted.await()
                sourceOfTruth.replay("sync")
                observer.awaitDataValue("sync")
                sourceOfTruth.replay("v1")
                observer.awaitDataValue("v1")
                store.invalidate(key)
                bookkeeper.gateNextSuccess()

                store.stream(key).test {
                    val stale = assertIs<StoreResult.Data<String>>(awaitItem())
                    assertEquals("v1", stale.value)
                    assertTrue(stale.isStale)
                    withContext(Dispatchers.Default) {
                        bookkeeper.successEntered.await()
                    }
                    sourceOfTruth.replay("v1")
                    expectNoEvents()

                    sourceOfTruth.publishExternal("external")
                    var observerExternal: StoreResult.Data<String>? = null
                    while (observerExternal == null) {
                        val item = observer.awaitItem()
                        if (item is StoreResult.Data && item.value == "external") {
                            observerExternal = item
                        }
                    }
                    assertExternalSotStale(assertNotNull(observerExternal))
                    bookkeeper.releaseSuccess.complete(Unit)

                    var sawFresh = false
                    while (!sawFresh) {
                        when (val item = awaitItem()) {
                            is StoreResult.Data -> {
                                if (item.value == "external") assertExternalSotStale(item)
                                assertFalse(
                                    item.value == "v1" && !item.isStale,
                                    "stale 304 revision must not directly re-emit v1 as fresh",
                                )
                                if (item.value == "fresh") {
                                    assertEquals(Origin.FETCHER, item.origin)
                                    assertFalse(item.isStale)
                                    sawFresh = true
                                }
                            }

                            is StoreResult.Revalidated ->
                                fail("obsolete 304 must not emit Revalidated")

                            else -> Unit
                        }
                    }
                    assertEquals(3, calls)
                    cancelAndIgnoreRemainingEvents()
                }
                observer.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            bookkeeper.releaseSuccess.complete(Unit)
            store.close()
        }
    }

    @Test
    fun notModifiedDirect_afterMappedSameValueBaseline_emitsRevalidatedExactlyOnce() = runTest {
        val sourceOfTruth = ReplayableSourceOfTruth()
        var calls = 0
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcherOfResult {
                when (++calls) {
                    1 -> FetcherResult.Success("v1", etag = "e1")
                    2 -> FetcherResult.NotModified(etag = "e2")
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(key))
            app.cash.turbine.turbineScope {
                val observer = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("v1", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
                sourceOfTruth.liveReaderStarted.await()
                sourceOfTruth.replay("sync")
                observer.awaitDataValue("sync")
                sourceOfTruth.replay("v1")
                observer.awaitDataValue("v1")
                store.invalidate(key)

                val collector = store.stream(key).testIn(backgroundScope)
                assertTrue(assertIs<StoreResult.Data<String>>(collector.awaitItem()).isStale)
                assertIs<StoreResult.Revalidated>(collector.awaitItem())
                collector.expectNoEvents()

                assertEquals(2, calls)
                observer.cancelAndIgnoreRemainingEvents()
                collector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun sameValueReplayThenNotModifiedDirect_emitsRevalidatedExactlyOnce() = runTest {
        val sourceOfTruth = ReplayableSourceOfTruth()
        val bookkeeper = GateNextSuccessBookkeeper()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        var calls = 0
        val store = storeWith<TestKey, String>(bookkeeper = bookkeeper) {
            persistence(sourceOfTruth)
            fetcherOfResult {
                when (++calls) {
                    1 -> FetcherResult.Success("v1", etag = "e1")
                    2 -> {
                        secondStarted.complete(Unit)
                        releaseSecond.await()
                        FetcherResult.NotModified(etag = "e2")
                    }
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(key))
            app.cash.turbine.turbineScope {
                val observer = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("v1", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
                sourceOfTruth.liveReaderStarted.await()
                sourceOfTruth.replay("sync")
                observer.awaitDataValue("sync")
                sourceOfTruth.replay("v1")
                observer.awaitDataValue("v1")
                store.invalidate(key)
                bookkeeper.gateNextSuccess()

                val collector = store.stream(key).testIn(backgroundScope)
                assertTrue(assertIs<StoreResult.Data<String>>(collector.awaitItem()).isStale)
                secondStarted.await()
                releaseSecond.complete(Unit)
                bookkeeper.successEntered.await()
                sourceOfTruth.replay("v1")
                collector.expectNoEvents()
                bookkeeper.releaseSuccess.complete(Unit)

                assertIs<StoreResult.Revalidated>(collector.awaitItem())
                collector.expectNoEvents()
                assertEquals(2, calls)
                observer.cancelAndIgnoreRemainingEvents()
                collector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseSecond.complete(Unit)
            bookkeeper.releaseSuccess.complete(Unit)
            store.close()
        }
    }

    @Test
    fun lateCollector_exactResidentRevisionEmitsMemoryOnce() = runTest {
        val store = store<TestKey, String> { fetcher { "v1" } }

        try {
            assertEquals("v1", store.get(key))
            store.stream(key, Freshness.LocalOnly).test {
                val data = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v1", data.value)
                assertEquals(Origin.MEMORY, data.origin)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun externalWriteReturned_whileGraceHasOldMemory_convergesMemoryThenSot() = runTest {
        val sourceOfTruth = GraceGateSourceOfTruth()
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { error("fetch must not run") }
        }

        try {
            app.cash.turbine.turbineScope {
                val first = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(first.awaitItem()).value)
                withContext(Dispatchers.Default) {
                    sourceOfTruth.liveReaderStarted.await()
                }
                first.cancelAndIgnoreRemainingEvents()

                sourceOfTruth.gateExternalEmission()
                sourceOfTruth.write(key, "external")
                withContext(Dispatchers.Default) {
                    sourceOfTruth.externalEmissionBlocked.await()
                }

                val second = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                val memory = assertIs<StoreResult.Data<String>>(second.awaitItem())
                assertEquals("seed", memory.value)
                assertEquals(Origin.MEMORY, memory.origin)

                sourceOfTruth.releaseExternalEmission.complete(Unit)
                val external = assertIs<StoreResult.Data<String>>(second.awaitItem())
                assertEquals("external", external.value)
                assertEquals(Origin.SOT, external.origin)
                second.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            sourceOfTruth.releaseExternalEmission.complete(Unit)
            store.close()
        }
    }

    @Test
    fun fetchFailureAfterReactiveAbsence_reportsServedStaleFalse() = runTest {
        val sourceOfTruth = MutableSourceOfTruth()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val boom = IllegalStateException("fetch failed")
        var calls = 0
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondStarted.complete(Unit)
                        releaseSecond.await()
                        throw boom
                    }

                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(key))
            store.invalidate(key)
            store.stream(key).test {
                val stale = assertIs<StoreResult.Data<String>>(awaitItem())
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)
                secondStarted.await()

                sourceOfTruth.delete(key)
                assertIs<StoreResult.Loading>(awaitItem())
                releaseSecond.complete(Unit)

                val failure = assertIs<StoreResult.Error>(awaitItem())
                assertIs<StoreError.Fetch>(failure.error)
                assertFalse(failure.servedStale)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseSecond.complete(Unit)
            store.close()
        }
    }

    @Test
    fun fetchFailureWhileReactiveStaleValueRemains_reportsServedStaleTrue() = runTest {
        val sourceOfTruth = MutableSourceOfTruth()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        var calls = 0
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        secondStarted.complete(Unit)
                        releaseSecond.await()
                        throw IllegalStateException("fetch failed")
                    }

                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            assertEquals("v1", store.get(key))
            store.invalidate(key)
            store.stream(key).test {
                val stale = assertIs<StoreResult.Data<String>>(awaitItem())
                assertTrue(stale.isStale)
                secondStarted.await()
                releaseSecond.complete(Unit)

                val failure = assertIs<StoreResult.Error>(awaitItem())
                assertIs<StoreError.Fetch>(failure.error)
                assertTrue(failure.servedStale)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseSecond.complete(Unit)
            store.close()
        }
    }

    @Test
    fun externalNullMetaRow_underMaxAgeEmitsLoadingUntilFresh() = runTest {
        val sourceOfTruth = MutableSourceOfTruth(initial = "durable")
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher {
                fetchStarted.complete(Unit)
                releaseFetch.await()
                "fresh"
            }
        }

        try {
            store.stream(key, Freshness.MaxAge(5.minutes)).test {
                assertIs<StoreResult.Loading>(awaitItem())
                fetchStarted.await()
                sourceOfTruth.liveReaderStarted.await()
                releaseFetch.complete(Unit)
                assertEquals("fresh", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseFetch.complete(Unit)
            store.close()
        }
    }

    @Test
    fun serverDeleteFailure_reportsTimestampedPersistence_withoutDestructiveMutation() = runTest {
        val boom = IllegalStateException("delete rejected")
        val sourceOfTruth = GatedFailingServerDeleteSourceOfTruth(failure = boom)
        val bookkeeper = FailureTrackingBookkeeper()
        val clock = FakeWallClock(now = 400L)
        var calls = 0
        val engine =
            KeyEngine(
                key = key,
                keyId = KeyId.from(key),
                fetcher = ResultFetcher {
                    when (++calls) {
                        1 -> FetcherResult.Success("v1", etag = "e1")
                        2 -> FetcherResult.Deleted
                        else -> error("unexpected fetch call $calls")
                    }
                },
                sot = sourceOfTruth,
                bookkeeper = bookkeeper,
                validator = DefaultFreshnessValidator,
                wallClock = clock,
                engineScope = backgroundScope,
            )

        val seed =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.get(Freshness.CachedOrFetch)
            }
        runCurrent()
        assertEquals("v1", seed.await())
        val stateBefore = engine.state.value
        val statusBefore = assertNotNull(bookkeeper.status(key))
        assertEquals("v1", sourceOfTruth.current)

        clock.now = 425L
        val deleting =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.get(Freshness.MustBeFresh) }
            }
        val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
        runCurrent()
        sourceOfTruth.deleteStarted.await()
        assertEquals(1, sourceOfTruth.deleteCalls)
        sourceOfTruth.releaseDelete.complete(Unit)

        val failure = assertIs<StoreException>(deleting.await().exceptionOrNull())
        val persistence = assertIs<StoreError.Persistence>(failure.error)
        assertTrue(persistence.cause === boom)
        assertTrue(persistence.message.contains("server-side deletion failed"))
        val outcome = assertIs<FetchOutcome.Failed>(ticket.outcome.await())
        assertEquals(425L, outcome.atEpochMillis)
        assertFalse(outcome.bookkeepingRecorded)
        assertIs<StoreError.Persistence>(outcome.exception.error)
        assertEquals(FetchDisposition.Failed, ticket.disposition.value)

        val stateAfter = engine.state.value
        assertEquals(stateBefore.staleEpoch, stateAfter.staleEpoch)
        assertEquals(stateBefore.clearEpoch, stateAfter.clearEpoch)
        assertEquals(stateBefore.readerGen, stateAfter.readerGen)
        assertTrue(stateAfter.attribution === stateBefore.attribution)
        assertEquals(FetchSlot.Idle, stateAfter.fetch)
        assertEquals("v1", sourceOfTruth.current)
        assertEquals("v1", engine.get(Freshness.LocalOnly))
        assertEquals(listOf(425L), bookkeeper.failureTimes)
        assertEquals(0, bookkeeper.forgetCalls)
        val statusAfter = assertNotNull(bookkeeper.status(key))
        assertTrue(statusAfter.meta === statusBefore.meta)
        assertEquals(statusBefore.lastSuccessSequence, statusAfter.lastSuccessSequence)
        assertEquals(425L, statusAfter.lastFailureAtEpochMillis)
        assertEquals(1, statusAfter.consecutiveFailures)
    }

    @Test
    fun clear_waitingForWriteLock_isCancellableBeforeDeleteStarts() = runTest {
        val sourceOfTruth = GatedDeleteSourceOfTruth()
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { error("fetch must not run") }
        }

        try {
            assertEquals("seed", store.get(key, Freshness.LocalOnly))
            val first =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.clear(key)
                }
            sourceOfTruth.deleteStarted.await()
            val second =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.clear(key)
                }
            runCurrent()

            second.cancel(CancellationException("cancel queued clear"))
            sourceOfTruth.releaseDelete.complete(Unit)
            first.await()
            assertIs<CancellationException>(runCatching { second.await() }.exceptionOrNull())
            assertEquals(1, sourceOfTruth.deleteCalls)
        } finally {
            sourceOfTruth.releaseDelete.complete(Unit)
            store.close()
        }
    }

    @Test
    fun clear_afterDeleteStarts_finishesIrreversibleTailUnderCancellation() = runTest {
        val sourceOfTruth = GatedDeleteSourceOfTruth()
        val bookkeeper = ForgetSignallingBookkeeper()
        val store = storeWith<TestKey, String>(bookkeeper = bookkeeper) {
            persistence(sourceOfTruth)
            fetcher { error("fetch must not run") }
        }

        try {
            assertEquals("seed", store.get(key, Freshness.LocalOnly))
            val clear =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.clear(key)
                }
            sourceOfTruth.deleteStarted.await()
            clear.cancel(CancellationException("cancel after delete started"))
            sourceOfTruth.releaseDelete.complete(Unit)
            withTimeout(2_000L) { bookkeeper.forgotten.await() }

            val missing =
                runCatching { store.get(key, Freshness.LocalOnly) }
                    .exceptionOrNull() as? StoreException
            assertIs<StoreError.Missing>(missing?.error)
            assertEquals(1, sourceOfTruth.deleteCalls)
        } finally {
            sourceOfTruth.releaseDelete.complete(Unit)
            store.close()
        }
    }

    @Test
    fun backpressuredReaderDelivery_doesNotBlockClearMutation() = runTest {
        val sourceOfTruth = BackpressureSourceOfTruth()
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { error("fetch must not run") }
        }
        val collector =
            store.stream(key, Freshness.LocalOnly)
                .buffer(capacity = 0)
                .produceIn(backgroundScope)

        try {
            assertEquals("seed", assertIs<StoreResult.Data<String>>(collector.receive()).value)
            sourceOfTruth.liveReaderStarted.await()
            sourceOfTruth.publishExternal("external")
            awaitLocalValue(store, "external")
            sourceOfTruth.externalReaderObserved.await()

            val clear =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.clear(key)
                }
            withContext(Dispatchers.Default) {
                sourceOfTruth.deleteStarted.await()
            }
            clear.await()
        } finally {
            collector.cancel()
            store.close()
        }
    }

    @Test
    fun synchronousDeleteEcho_doesNotLaunchFetchBeforeClearTail() = runTest {
        val sourceOfTruth = GatedDeleteEchoSourceOfTruth()
        var fetchCalls = 0
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "fetched-${++fetchCalls}" }
        }

        try {
            assertEquals("fetched-1", store.get(key))
            app.cash.turbine.turbineScope {
                val collector = store.stream(key).testIn(backgroundScope)
                assertEquals(
                    "fetched-1",
                    assertIs<StoreResult.Data<String>>(collector.awaitItem()).value,
                )
                sourceOfTruth.liveReaderStarted.await()

                val clear =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        store.clear(key)
                    }
                sourceOfTruth.deleteEchoPublished.await()
                assertEquals(1, fetchCalls)

                sourceOfTruth.releaseDelete.complete(Unit)
                clear.await()
                collector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            sourceOfTruth.releaseDelete.complete(Unit)
            store.close()
        }
    }

    @Test
    fun externalAbsentAfterVisibleLocalData_emitsLoadingThenOneMissingAndResetsDedup() = runTest {
        val sourceOfTruth = MutableSourceOfTruth(initial = "seed")
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { error("fetch must not run") }
        }

        try {
            store.stream(key, Freshness.LocalOnly).test {
                assertEquals("seed", assertIs<StoreResult.Data<String>>(awaitItem()).value)

                sourceOfTruth.delete(key)
                assertIs<StoreResult.Loading>(awaitItem())
                assertIs<StoreError.Missing>(assertIs<StoreResult.Error>(awaitItem()).error)
                expectNoEvents()

                sourceOfTruth.write(key, "seed")
                assertEquals("seed", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun committedCachedStream_waitsForSourceOfTruthReaderRowBeforeData() = runTest {
        val sourceOfTruth = WithheldReaderEchoSourceOfTruth()
        val bookkeeper = GateNextSuccessBookkeeper().also { it.gateNextSuccess() }
        val store = storeWith<TestKey, String>(bookkeeper = bookkeeper) {
            persistence(sourceOfTruth)
            fetcher {
                sourceOfTruth.liveReaderStarted.await()
                "fetched"
            }
        }

        try {
            store.stream(key).test {
                assertIs<StoreResult.Loading>(awaitItem())
                sourceOfTruth.writeReturned.await()
                bookkeeper.successEntered.await()
                expectNoEvents()

                bookkeeper.releaseSuccess.complete(Unit)
                sourceOfTruth.liveReaderStarted.await()
                expectNoEvents()
                sourceOfTruth.releaseReaderEcho.complete(Unit)
                assertEquals("fetched", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            bookkeeper.releaseSuccess.complete(Unit)
            sourceOfTruth.releaseReaderEcho.complete(Unit)
            store.close()
        }
    }

    @Test
    fun committedMustBeFreshStream_waitsForSourceOfTruthReaderRowBeforeData() = runTest {
        val sourceOfTruth = WithheldReaderEchoSourceOfTruth()
        val bookkeeper = GateNextSuccessBookkeeper().also { it.gateNextSuccess() }
        val store = storeWith<TestKey, String>(bookkeeper = bookkeeper) {
            persistence(sourceOfTruth)
            fetcher { "fetched" }
        }

        try {
            store.stream(key, Freshness.MustBeFresh).test {
                assertIs<StoreResult.Loading>(awaitItem())
                sourceOfTruth.writeReturned.await()
                bookkeeper.successEntered.await()
                expectNoEvents()

                bookkeeper.releaseSuccess.complete(Unit)
                sourceOfTruth.liveReaderStarted.await()
                expectNoEvents()
                sourceOfTruth.releaseReaderEcho.complete(Unit)
                assertEquals("fetched", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            bookkeeper.releaseSuccess.complete(Unit)
            sourceOfTruth.releaseReaderEcho.complete(Unit)
            store.close()
        }
    }

    @Test
    fun newCollectorDuringSettleToWrite_canLaunchOneRedundantFetch() = runTest {
        val sourceOfTruth = GatedWriteTailSourceOfTruth()
        val secondFetchStarted = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher {
                val call = ++fetchCalls
                if (call == 2) secondFetchStarted.complete(Unit)
                "fetched-$call"
            }
        }

        try {
            assertEquals("seed", store.get(key, Freshness.LocalOnly))
            store.invalidate(key)

            app.cash.turbine.turbineScope {
                val first = store.stream(key).testIn(backgroundScope)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(first.awaitItem()).value)
                sourceOfTruth.writeStarted.await()

                val second = store.stream(key).testIn(backgroundScope)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(second.awaitItem()).value)
                secondFetchStarted.await()
                assertEquals(2, fetchCalls)

                sourceOfTruth.releaseWrite.complete(Unit)
                first.cancelAndIgnoreRemainingEvents()
                second.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            sourceOfTruth.releaseWrite.complete(Unit)
            store.close()
        }
    }

    @Test
    fun preSubscribedCollectors_waitThroughQueuedAbsentThenDeliverWriterCurrentEcho() = runTest {
        val sourceOfTruth = QueuedAbsentWriteSourceOfTruth()
        val firstFetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val secondFetchStarted = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher {
                val call = ++fetchCalls
                when (call) {
                    1 -> {
                        firstFetchStarted.complete(Unit)
                        releaseFetch.await()
                        "fresh"
                    }

                    2 -> {
                        secondFetchStarted.complete(Unit)
                        "unexpected"
                    }

                    else -> error("unexpected fetch call $call")
                }
            }
        }

        try {
            assertEquals("seed", store.get(key, Freshness.LocalOnly))
            store.invalidate(key)

            app.cash.turbine.turbineScope {
                val first = store.stream(key).testIn(backgroundScope)
                val second = store.stream(key).testIn(backgroundScope)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(first.awaitItem()).value)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(second.awaitItem()).value)
                firstFetchStarted.await()
                sourceOfTruth.liveReaderStarted.await()
                assertEquals(1, fetchCalls)

                releaseFetch.complete(Unit)
                sourceOfTruth.queuedAbsentEmitted.await()
                assertIs<StoreResult.Loading>(first.awaitItem())
                assertIs<StoreResult.Loading>(second.awaitItem())

                sourceOfTruth.releaseWriterEcho.complete(Unit)
                val firstEcho = assertIs<StoreResult.Data<String>>(first.awaitItem())
                val secondEcho = assertIs<StoreResult.Data<String>>(second.awaitItem())
                assertEquals("fresh", firstEcho.value)
                assertEquals("fresh", secondEcho.value)
                assertEquals(Origin.FETCHER, firstEcho.origin)
                assertEquals(Origin.FETCHER, secondEcho.origin)
                assertFalse(firstEcho.refreshing)
                assertFalse(secondEcho.refreshing)
                testScheduler.runCurrent()
                assertFalse(secondFetchStarted.isCompleted)
                assertEquals(1, fetchCalls)
                first.expectNoEvents()
                second.expectNoEvents()
                first.cancelAndIgnoreRemainingEvents()
                second.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseFetch.complete(Unit)
            sourceOfTruth.releaseWriterEcho.complete(Unit)
            store.close()
        }
    }

    @Test
    fun externalDeleteAfterWriteReturnsBeforeOutcome_isAuthoritativeAndReplans() = runTest {
        val sourceOfTruth = MutableSourceOfTruth(initial = "seed")
        val bookkeeper = GateNextSuccessBookkeeper()
        val firstFetchStarted = CompletableDeferred<Unit>()
        val releaseFirstFetch = CompletableDeferred<Unit>()
        val secondFetchStarted = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val store = storeWith<TestKey, String>(bookkeeper = bookkeeper) {
            persistence(sourceOfTruth)
            fetcher {
                when (++fetchCalls) {
                    1 -> {
                        firstFetchStarted.complete(Unit)
                        releaseFirstFetch.await()
                        "candidate"
                    }
                    2 -> {
                        secondFetchStarted.complete(Unit)
                        "recovered"
                    }

                    else -> error("unexpected fetch call $fetchCalls")
                }
            }
        }

        try {
            assertEquals("seed", store.get(key, Freshness.LocalOnly))
            bookkeeper.gateNextSuccess()

            store.stream(key).test {
                assertEquals("seed", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                firstFetchStarted.await()
                releaseFirstFetch.complete(Unit)
                withContext(Dispatchers.Default) {
                    bookkeeper.successEntered.await()
                }
                assertEquals("candidate", assertIs<StoreResult.Data<String>>(awaitItem()).value)

                sourceOfTruth.delete(key)
                assertIs<StoreResult.Loading>(awaitItem())
                bookkeeper.releaseSuccess.complete(Unit)

                withContext(Dispatchers.Default) {
                    secondFetchStarted.await()
                }
                assertEquals("recovered", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                assertEquals(2, fetchCalls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseFirstFetch.complete(Unit)
            bookkeeper.releaseSuccess.complete(Unit)
            store.close()
        }
    }

    @Test
    fun conformingIntermediateAbsent_convergesFinalWriterWithoutRevalidation() = runTest {
        val sourceOfTruth = GatedAppliedWriteSourceOfTruth()
        val firstFetchStarted = CompletableDeferred<Unit>()
        val releaseFirstFetch = CompletableDeferred<Unit>()
        val secondFetchStarted = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher {
                when (val call = ++fetchCalls) {
                    1 -> {
                        firstFetchStarted.complete(Unit)
                        releaseFirstFetch.await()
                        "candidate"
                    }

                    else -> {
                        secondFetchStarted.complete(Unit)
                        error("unexpected fetch call $call")
                    }
                }
            }
        }

        try {
            assertEquals("seed", store.get(key, Freshness.LocalOnly))
            app.cash.turbine.turbineScope {
                val observer = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
                sourceOfTruth.liveReaderStarted.await()
                sourceOfTruth.publishExternal("sync")
                assertEquals("sync", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
                sourceOfTruth.publishExternal("seed")
                assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)

                val collector = store.stream(key).testIn(backgroundScope)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
                firstFetchStarted.await()
                observer.cancelAndIgnoreRemainingEvents()
                releaseFirstFetch.complete(Unit)
                sourceOfTruth.firstWriteApplied.await()

                sourceOfTruth.publishExternalDelete()
                sourceOfTruth.releaseFirstWrite.complete(Unit)

                val candidate = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("candidate", candidate.value)
                assertEquals(Origin.FETCHER, candidate.origin)
                assertFalse(candidate.isStale)
                assertFalse(candidate.refreshing)
                assertFalse(secondFetchStarted.isCompleted)
                assertEquals(1, fetchCalls)
                collector.expectNoEvents()
                collector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseFirstFetch.complete(Unit)
            sourceOfTruth.releaseFirstWrite.complete(Unit)
            store.close()
        }
    }

    @Test
    fun hydratedNullMeta_staleIfErrorWaitsForFailureBeforeFallingBack() = runTest {
        val sourceOfTruth = MutableSourceOfTruth(initial = "durable")
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher {
                fetchStarted.complete(Unit)
                releaseFetch.await()
                throw IllegalStateException("offline")
            }
        }

        try {
            val read =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.get(key, Freshness.StaleIfError)
                }
            fetchStarted.await()
            assertFalse(read.isCompleted)

            releaseFetch.complete(Unit)
            assertEquals("durable", read.await())
        } finally {
            releaseFetch.complete(Unit)
            store.close()
        }
    }

    @Test
    fun activeMaxAgeCollector_withholdsWriterRowThatExpiresBeforeReaderDelivery() = runTest {
        val clock = FakeWallClock(now = 0L)
        val sourceOfTruth = GatedSecondWriteSourceOfTruth()
        val thirdFetchStarted = CompletableDeferred<Unit>()
        var calls = 0
        val store = storeWith<TestKey, String>(clock = clock) {
            persistence(sourceOfTruth)
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> "v2"
                    3 -> {
                        thirdFetchStarted.complete(Unit)
                        "v3"
                    }

                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        try {
            app.cash.turbine.turbineScope {
                val observer = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
                sourceOfTruth.liveReaderStarted.await()
                sourceOfTruth.publishExternal("sync")
                assertEquals("sync", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
                sourceOfTruth.publishExternal("seed")
                assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)

                assertEquals("v1", store.get(key, Freshness.MustBeFresh))
                val hydrated = assertIs<StoreResult.Data<String>>(observer.awaitItem())
                assertEquals("v1", hydrated.value)
                assertEquals(Origin.FETCHER, hydrated.origin)

                val collector =
                    store.stream(key, Freshness.MaxAge(5.minutes)).testIn(backgroundScope)
                assertEquals("v1", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
                store.invalidate(key)
                assertIs<StoreResult.Loading>(collector.awaitItem())
                withContext(Dispatchers.Default) {
                    sourceOfTruth.secondWriteStarted.await()
                }

                clock.now = 10.minutes.inWholeMilliseconds
                sourceOfTruth.releaseSecondWrite.complete(Unit)

                withContext(Dispatchers.Default) {
                    thirdFetchStarted.await()
                }
                assertEquals(
                    "v3",
                    assertIs<StoreResult.Data<String>>(collector.awaitItem()).value,
                )
                assertEquals(3, calls)
                observer.cancelAndIgnoreRemainingEvents()
                collector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            sourceOfTruth.releaseSecondWrite.complete(Unit)
            store.close()
        }
    }

    @Test
    fun mustBeFreshCommittedOutcome_doesNotDeliverNewerExternalNullMetaRow() = runTest {
        val sourceOfTruth = FirstWriteWithheldSourceOfTruth()
        val bookkeeper = GateNextSuccessBookkeeper()
        var fetchCalls = 0
        val store = storeWith<TestKey, String>(bookkeeper = bookkeeper) {
            persistence(sourceOfTruth)
            fetcher { if (++fetchCalls == 1) "candidate" else "fresh" }
        }

        try {
            app.cash.turbine.turbineScope {
                val observer = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
                bookkeeper.gateNextSuccess()

                val fresh = store.stream(key, Freshness.MustBeFresh).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(fresh.awaitItem())
                sourceOfTruth.firstWriteReturned.await()
                bookkeeper.successEntered.await()

                sourceOfTruth.publishExternal("external")
                while (true) {
                    val item = observer.awaitItem()
                    if (item is StoreResult.Data && item.value == "external") break
                }
                bookkeeper.releaseSuccess.complete(Unit)

                assertEquals("fresh", assertIs<StoreResult.Data<String>>(fresh.awaitItem()).value)
                assertEquals(2, fetchCalls)
                observer.cancelAndIgnoreRemainingEvents()
                fresh.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            bookkeeper.releaseSuccess.complete(Unit)
            store.close()
        }
    }

    @Test
    fun queuedAbsentBeforeWriterEcho_getReturnsCommitAndPipelineKeepsHonestOrigin() = runTest {
        val sourceOfTruth = QueuedAbsentAfterSeedSourceOfTruth()
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "candidate" }
        }

        try {
            assertEquals("seed", store.get(key, Freshness.LocalOnly))
            app.cash.turbine.turbineScope {
                val observer = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
                sourceOfTruth.liveReaderStarted.await()
                sourceOfTruth.publishExternal("sync")
                assertEquals("sync", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
                sourceOfTruth.publishExternal("seed")
                assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
                store.invalidate(key)

                val fetched =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        store.get(key, Freshness.MustBeFresh)
                    }
                sourceOfTruth.queuedAbsentEmitted.await()
                assertIs<StoreResult.Loading>(observer.awaitItem())
                assertIs<StoreError.Missing>(
                    assertIs<StoreResult.Error>(observer.awaitItem()).error,
                )

                sourceOfTruth.releaseWriterEcho.complete(Unit)
                assertEquals("candidate", fetched.await())
                val echo = assertIs<StoreResult.Data<String>>(observer.awaitItem())
                assertEquals("candidate", echo.value)
                assertEquals(Origin.FETCHER, echo.origin)
                assertFalse(echo.isStale)
                observer.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            sourceOfTruth.releaseWriterEcho.complete(Unit)
            store.close()
        }
    }

    @Test
    fun closeDuringSourceOfTruthWrite_cancelsWriteAndDoesNotRecordSuccess() = runTest {
        val sourceOfTruth = CancellationWriteSourceOfTruth()
        val bookkeeper = InMemoryBookkeeper()
        val store = storeWith<TestKey, String>(bookkeeper = bookkeeper) {
            persistence(sourceOfTruth)
            fetcher { "value" }
        }

        val read =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { store.get(key) }
            }
        sourceOfTruth.writeStarted.await()
        store.close()

        withContext(Dispatchers.Default) {
            sourceOfTruth.writeCancelled.await()
        }
        assertNotNull(read.await().exceptionOrNull())
        assertNull(sourceOfTruth.current)
        assertNull(bookkeeper.status(key))
    }

    @Test
    fun closeDuringServerDelete_finishesDeleteStateAndBookkeeperTail() = runTest {
        val sourceOfTruth = GatedServerDeleteSourceOfTruth()
        val bookkeeper = ForgetSignallingBookkeeper()
        var calls = 0
        val store = storeWith<TestKey, String>(bookkeeper = bookkeeper) {
            persistence(sourceOfTruth)
            fetcherOfResult {
                when (++calls) {
                    1 -> FetcherResult.Success("v1")
                    2 -> FetcherResult.Deleted
                    else -> error("unexpected fetch call $calls")
                }
            }
        }

        assertEquals("v1", store.get(key))
        val deleting =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { store.get(key, Freshness.MustBeFresh) }
            }
        sourceOfTruth.deleteStarted.await()
        store.close()
        sourceOfTruth.releaseDelete.complete(Unit)

        withContext(Dispatchers.Default) {
            bookkeeper.forgotten.await()
        }
        assertNotNull(deleting.await().exceptionOrNull())
        assertNull(sourceOfTruth.current)
        assertNull(bookkeeper.status(key))
    }

    private suspend fun awaitLocalValue(
        store: Store<TestKey, String>,
        expected: String,
    ) {
        // Preserve Default-dispatch ordering and let the suite-level runTest bound own cancellation.
        withContext(Dispatchers.Default) {
            store.stream(key, Freshness.LocalOnly).first { result ->
                result is StoreResult.Data && result.value == expected
            }
        }
    }

    /** Waits for the already-active observer to deliver [expected], proving its pipeline caught up. */
    private suspend fun ReceiveTurbine<StoreResult<String>>.awaitDataValue(expected: String) {
        while (true) {
            val item = awaitItem()
            if (item is StoreResult.Data && item.value == expected) return
        }
    }

    private fun assertExternalSotStale(data: StoreResult.Data<String>) {
        assertEquals("external", data.value)
        assertEquals(Origin.SOT, data.origin)
        assertTrue(data.isStale)
    }

    private class GatedFailingServerDeleteSourceOfTruth(
        private val failure: Throwable,
    ) : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>(null)
        val deleteStarted = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        var deleteCalls: Int = 0
            private set
        val current: String?
            get() = rows.value

        override fun reader(key: TestKey): Flow<String?> = rows

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            deleteCalls += 1
            deleteStarted.complete(Unit)
            releaseDelete.await()
            throw failure
        }
    }

    private class FailureTrackingBookkeeper : Bookkeeper {
        private val delegate = InMemoryBookkeeper()
        val failureTimes = mutableListOf<Long>()
        var forgetCalls: Int = 0
            private set

        override suspend fun recordSuccess(
            key: StoreKey,
            meta: StoreMeta,
        ) {
            delegate.recordSuccess(key, meta)
        }

        override suspend fun recordFailure(
            key: StoreKey,
            atEpochMillis: Long,
        ) {
            failureTimes += atEpochMillis
            delegate.recordFailure(key, atEpochMillis)
        }

        override suspend fun status(key: StoreKey): KeyStatus? = delegate.status(key)

        override suspend fun forget(key: StoreKey) {
            forgetCalls += 1
            delegate.forget(key)
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

    private class MutableSourceOfTruth(
        initial: String? = null,
    ) : SingleRowTestSourceOfTruth<String> {
        private data class VersionedRow(
            val value: String?,
            val version: Long,
        )

        private val rows = MutableStateFlow(VersionedRow(initial, version = 0L))
        private var readerCalls = 0
        val liveReaderStarted = CompletableDeferred<Unit>()
        var deleteFailure: Throwable? = null

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            if (readerCalls >= 2) liveReaderStarted.complete(Unit)
            return flow {
                rows.collect { row -> emit(row.value) }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            publish(value)
        }

        override suspend fun delete(key: TestKey) {
            deleteFailure?.let { throw it }
            publish(null)
        }

        fun publishExternal(value: String) {
            publish(value)
        }

        private fun publish(value: String?) {
            rows.value =
                VersionedRow(
                    value = value,
                    version = rows.value.version + 1L,
                )
        }
    }

    private class ReplayableSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = kotlinx.coroutines.flow.MutableSharedFlow<String?>(replay = 1)
        private var readerCalls = 0
        private var pendingReplayObservation: CompletableDeferred<Unit>? = null
        val liveReaderStarted = CompletableDeferred<Unit>()

        init {
            rows.tryEmit(null)
        }

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            if (readerCalls >= 2) liveReaderStarted.complete(Unit)
            return flow {
                rows.collect { row ->
                    emit(row)
                    pendingReplayObservation?.complete(Unit)
                }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.emit(value)
        }

        override suspend fun delete(key: TestKey) {
            rows.emit(null)
        }

        suspend fun replay(value: String) {
            val observed = CompletableDeferred<Unit>()
            check(pendingReplayObservation == null) { "A replay acknowledgement is already pending." }
            pendingReplayObservation = observed
            rows.emit(value)
            observed.await()
            if (pendingReplayObservation === observed) pendingReplayObservation = null
        }

        suspend fun publishExternal(value: String) {
            rows.emit(value)
        }
    }

    private class GatedHydrationSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>("stale")
        private var gateFirstReader = true
        val readerStarted = CompletableDeferred<Unit>()
        val releaseReader = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            if (!gateFirstReader) return rows
            gateFirstReader = false
            val snapshot = rows.value
            return flow {
                readerStarted.complete(Unit)
                releaseReader.await()
                emit(snapshot)
                awaitCancellation()
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            rows.value = null
        }
    }

    private class GateNextSuccessBookkeeper : Bookkeeper {
        private val delegate = InMemoryBookkeeper()
        private var gateNext = false
        val successEntered = CompletableDeferred<Unit>()
        val releaseSuccess = CompletableDeferred<Unit>()

        fun gateNextSuccess() {
            gateNext = true
        }

        override suspend fun recordSuccess(
            key: StoreKey,
            meta: StoreMeta,
        ) {
            if (gateNext) {
                gateNext = false
                successEntered.complete(Unit)
                releaseSuccess.await()
            }
            delegate.recordSuccess(key, meta)
        }

        override suspend fun recordFailure(
            key: StoreKey,
            atEpochMillis: Long,
        ) {
            delegate.recordFailure(key, atEpochMillis)
        }

        override suspend fun status(key: StoreKey): KeyStatus? = delegate.status(key)

        override suspend fun forget(key: StoreKey) {
            delegate.forget(key)
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

    private class GraceGateSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>("seed")
        private var readerCalls = 0
        private var gateExternal = false
        val liveReaderStarted = CompletableDeferred<Unit>()
        val externalEmissionBlocked = CompletableDeferred<Unit>()
        val releaseExternalEmission = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            val call = readerCalls
            return flow {
                if (call >= 2) liveReaderStarted.complete(Unit)
                rows.collect { row ->
                    if (gateExternal && row == "external") {
                        externalEmissionBlocked.complete(Unit)
                        releaseExternalEmission.await()
                    }
                    emit(row)
                }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            rows.value = null
        }

        fun gateExternalEmission() {
            gateExternal = true
        }
    }

    private class GatedDeleteSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>("seed")
        val deleteStarted = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        var deleteCalls: Int = 0

        override fun reader(key: TestKey): Flow<String?> = rows

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            deleteCalls += 1
            deleteStarted.complete(Unit)
            releaseDelete.await()
            rows.value = null
        }
    }

    private class BackpressureSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>("seed")
        private var readerCalls = 0
        val deleteStarted = CompletableDeferred<Unit>()
        val liveReaderStarted = CompletableDeferred<Unit>()
        val externalReaderObserved = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            if (readerCalls >= 2) liveReaderStarted.complete(Unit)
            return flow {
                rows.collect { row ->
                    emit(row)
                    if (row == "external") externalReaderObserved.complete(Unit)
                }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            deleteStarted.complete(Unit)
            rows.value = null
        }

        fun publishExternal(value: String) {
            rows.value = value
        }
    }

    private class WithheldReaderEchoSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>(null)
        val liveReaderStarted = CompletableDeferred<Unit>()
        val writeReturned = CompletableDeferred<Unit>()
        val releaseReaderEcho = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> =
            flow {
                val initial = rows.value
                if (initial != null) {
                    // A long-lived reader may start after the write. Mark it live, but withhold its
                    // immediate current row behind the same delivery gate as an active-reader echo.
                    liveReaderStarted.complete(Unit)
                    releaseReaderEcho.await()
                }
                emit(initial)

                // A one-shot hydration probe is cancelled by `first()` during the emit above and
                // never reaches this collection. A long-lived reader either observes the same
                // snapshot as its first StateFlow callback or a write that raced the handoff.
                var firstStateFlowEmission = true
                rows.collect { row ->
                    if (firstStateFlowEmission) {
                        firstStateFlowEmission = false
                        liveReaderStarted.complete(Unit)
                        if (row == initial) return@collect
                    }
                    releaseReaderEcho.await()
                    emit(row)
                }
            }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.value = value
            writeReturned.complete(Unit)
        }

        override suspend fun delete(key: TestKey) {
            rows.value = null
        }
    }

    private class GatedDeleteEchoSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>(null)
        private var readerCalls = 0
        private val deleteInitiated = CompletableDeferred<Unit>()
        val liveReaderStarted = CompletableDeferred<Unit>()
        val deleteEchoPublished = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            if (readerCalls >= 2) liveReaderStarted.complete(Unit)
            return flow {
                rows.collect { row ->
                    emit(row)
                    if (row == null && deleteInitiated.isCompleted) {
                        deleteEchoPublished.complete(Unit)
                    }
                }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            deleteInitiated.complete(Unit)
            rows.value = null
            releaseDelete.await()
        }
    }

    private class GatedWriteTailSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>("seed")
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> = rows

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            writeStarted.complete(Unit)
            releaseWrite.await()
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            rows.value = null
        }
    }

    private class QueuedAbsentWriteSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val liveRows = kotlinx.coroutines.flow.MutableSharedFlow<String?>()
        private var readerCalls = 0
        val liveReaderStarted = CompletableDeferred<Unit>()
        val queuedAbsentEmitted = CompletableDeferred<Unit>()
        val releaseWriterEcho = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            if (readerCalls == 1) return flow { emit("seed") }
            liveReaderStarted.complete(Unit)
            return liveRows
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            liveRows.emit(null)
            queuedAbsentEmitted.complete(Unit)
            releaseWriterEcho.await()
            liveRows.emit(value)
        }

        override suspend fun delete(key: TestKey) {
            liveRows.emit(null)
        }

        suspend fun publishExternal(value: String) {
            liveRows.emit(value)
        }
    }

    private class GatedAppliedWriteSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = kotlinx.coroutines.flow.MutableSharedFlow<String?>(replay = 1)
        private val pendingDeleteObservation = MutableStateFlow<CompletableDeferred<Unit>?>(null)
        private var readerCalls = 0
        private var writes = 0
        val liveReaderStarted = CompletableDeferred<Unit>()
        val firstWriteApplied = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()

        init {
            rows.tryEmit("seed")
        }

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            if (readerCalls >= 2) liveReaderStarted.complete(Unit)
            return flow {
                rows.collect { row ->
                    emit(row)
                    if (row == null) pendingDeleteObservation.value?.complete(Unit)
                }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            writes += 1
            if (writes == 1) {
                rows.emit(value)
                firstWriteApplied.complete(Unit)
                releaseFirstWrite.await()
            }
            rows.emit(value)
        }

        override suspend fun delete(key: TestKey) {
            rows.emit(null)
        }

        suspend fun publishExternalDelete() {
            val observed = CompletableDeferred<Unit>()
            check(pendingDeleteObservation.compareAndSet(null, observed))
            rows.emit(null)
            observed.await()
            pendingDeleteObservation.compareAndSet(observed, null)
        }

        suspend fun publishExternal(value: String) {
            rows.emit(value)
        }
    }

    private class GatedSecondWriteSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>("seed")
        private var readerCalls = 0
        private var writes = 0
        val liveReaderStarted = CompletableDeferred<Unit>()
        val secondWriteStarted = CompletableDeferred<Unit>()
        val releaseSecondWrite = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            if (readerCalls >= 2) liveReaderStarted.complete(Unit)
            return rows
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            writes += 1
            if (writes == 2) {
                secondWriteStarted.complete(Unit)
                releaseSecondWrite.await()
            }
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            rows.value = null
        }

        fun publishExternal(value: String) {
            rows.value = value
        }
    }

    private class QueuedAbsentAfterSeedSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = kotlinx.coroutines.flow.MutableSharedFlow<String?>(replay = 1)
        private var readerCalls = 0
        val liveReaderStarted = CompletableDeferred<Unit>()
        val queuedAbsentEmitted = CompletableDeferred<Unit>()
        val releaseWriterEcho = CompletableDeferred<Unit>()

        init {
            rows.tryEmit("seed")
        }

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            if (readerCalls >= 2) liveReaderStarted.complete(Unit)
            return rows
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.emit(null)
            queuedAbsentEmitted.complete(Unit)
            releaseWriterEcho.await()
            rows.emit(value)
        }

        override suspend fun delete(key: TestKey) {
            rows.emit(null)
        }

        suspend fun publishExternal(value: String) {
            rows.emit(value)
        }
    }

    private class CancellationWriteSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>(null)
        val writeStarted = CompletableDeferred<Unit>()
        val writeCancelled = CompletableDeferred<Unit>()
        val current: String?
            get() = rows.value

        override fun reader(key: TestKey): Flow<String?> = rows

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            writeStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                writeCancelled.complete(Unit)
            }
        }

        override suspend fun delete(key: TestKey) {
            rows.value = null
        }
    }

    private class GatedServerDeleteSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>(null)
        val deleteStarted = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        val current: String?
            get() = rows.value

        override fun reader(key: TestKey): Flow<String?> = rows

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            deleteStarted.complete(Unit)
            releaseDelete.await()
            rows.value = null
        }
    }

    private class ForgetSignallingBookkeeper : Bookkeeper {
        private val delegate = InMemoryBookkeeper()
        val forgotten = CompletableDeferred<Unit>()

        override suspend fun recordSuccess(
            key: StoreKey,
            meta: StoreMeta,
        ) {
            delegate.recordSuccess(key, meta)
        }

        override suspend fun recordFailure(
            key: StoreKey,
            atEpochMillis: Long,
        ) {
            delegate.recordFailure(key, atEpochMillis)
        }

        override suspend fun status(key: StoreKey): KeyStatus? = delegate.status(key)

        override suspend fun forget(key: StoreKey) {
            delegate.forget(key)
            forgotten.complete(Unit)
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

    private class FirstWriteWithheldSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>("seed")
        private var writes = 0
        val firstWriteReturned = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> = rows

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            writes += 1
            if (writes == 1) {
                rows.value = value
                firstWriteReturned.complete(Unit)
            } else {
                rows.value = value
            }
        }

        override suspend fun delete(key: TestKey) {
            rows.value = null
        }

        fun publishExternal(value: String) {
            rows.value = value
        }
    }
}

// Turbine's 3s default nests inside the 25s shadow; raise the Turbine deadline above the
// shadow so runTest provides the only effective timeout.
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
