package org.mobilenativefoundation.store6.core

import app.cash.turbine.testIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.internal.DefaultFreshnessValidator
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.KeyEngine
import org.mobilenativefoundation.store6.core.internal.KeyId
import org.mobilenativefoundation.store6.core.internal.LambdaFetcher
import org.mobilenativefoundation.store6.core.internal.READER_PIPELINE_GRACE_MILLIS
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)
class SourceOfTruthAdditionalRaceTest {
    private val key = TestKey("additional-race")

    @Test
    fun externalWriteReturned_whileGraceReplayAbsent_streamServesDurableBeforeFetch() = runTest {
        val sourceOfTruth = GraceReplaySourceOfTruth(initial = null)
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val engine =
            newEngine(sourceOfTruth) {
                fetchCalls += 1
                fetchStarted.complete(Unit)
                releaseFetch.await()
                "fetched"
            }

        try {
            app.cash.turbine.turbineScope {
                val first = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
                assertIs<StoreError.Missing>(
                    assertIs<StoreResult.Error>(first.awaitItem()).error,
                )
                sourceOfTruth.liveReaderStarted.await()
                runCurrent()
                first.cancelAndIgnoreRemainingEvents()
                advanceToLastReaderGraceMillisecond()

                sourceOfTruth.gateNextLiveDelivery()
                sourceOfTruth.write(key, "durable")
                sourceOfTruth.liveDeliveryBlocked.await()

                val second = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
                val durable = assertIs<StoreResult.Data<String>>(second.awaitItem())
                assertEquals("durable", durable.value)
                assertEquals(Origin.SOT, durable.origin)
                assertTrue(durable.isStale)
                assertTrue(durable.refreshing)
                fetchStarted.await()
                assertEquals(1, fetchCalls)
                runCurrent()

                sourceOfTruth.releaseLiveDelivery.complete(Unit)
                runCurrent()
                releaseFetch.complete(Unit)
                while (true) {
                    val item = second.awaitItem()
                    if (item is StoreResult.Data && item.value == "fetched") break
                }
                second.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            sourceOfTruth.releaseLiveDelivery.complete(Unit)
            releaseFetch.complete(Unit)
        }
    }

    @Test
    fun externalDeleteReturned_whileGraceHasOldMemory_streamConvergesMemoryThenLoading() = runTest {
        val sourceOfTruth = GraceReplaySourceOfTruth(initial = "seed")
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val engine =
            newEngine(sourceOfTruth) {
                fetchCalls += 1
                fetchStarted.complete(Unit)
                releaseFetch.await()
                "fresh"
            }

        try {
            app.cash.turbine.turbineScope {
                val first = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(first.awaitItem()).value)
                sourceOfTruth.liveReaderStarted.await()
                runCurrent()
                first.cancelAndIgnoreRemainingEvents()
                advanceToLastReaderGraceMillisecond()

                sourceOfTruth.gateNextLiveDelivery()
                sourceOfTruth.delete(key)
                sourceOfTruth.liveDeliveryBlocked.await()

                val second = engine.stream(Freshness.CachedOrFetch).testIn(backgroundScope)
                val memory = assertIs<StoreResult.Data<String>>(second.awaitItem())
                assertEquals("seed", memory.value)
                assertEquals(Origin.MEMORY, memory.origin)
                fetchStarted.await()
                runCurrent()

                sourceOfTruth.releaseLiveDelivery.complete(Unit)
                assertIs<StoreResult.Loading>(second.awaitItem())
                releaseFetch.complete(Unit)
                while (true) {
                    val item = second.awaitItem()
                    if (item is StoreResult.Data && item.value == "fresh") break
                }
                assertEquals(1, fetchCalls)
                second.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            sourceOfTruth.releaseLiveDelivery.complete(Unit)
            releaseFetch.complete(Unit)
        }
    }

    @Test
    fun sameGenerationRowReplay_afterResidenceAdvances_neverRegresses() = runTest {
        val sourceOfTruth = GraceReplaySourceOfTruth(initial = "old")
        val engine = newEngine(sourceOfTruth) { "fresh" }

        try {
            app.cash.turbine.turbineScope {
                val first = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("old", assertIs<StoreResult.Data<String>>(first.awaitItem()).value)
                sourceOfTruth.liveReaderStarted.await()
                runCurrent()
                first.cancelAndIgnoreRemainingEvents()
                advanceToLastReaderGraceMillisecond()

                sourceOfTruth.gateNextLiveDelivery()
                val refresh =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        engine.get(Freshness.MustBeFresh)
                    }
                sourceOfTruth.liveDeliveryBlocked.await()
                assertEquals("fresh", refresh.await())

                val second = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("fresh", assertIs<StoreResult.Data<String>>(second.awaitItem()).value)
                runCurrent()
                second.expectNoEvents()

                sourceOfTruth.releaseLiveDelivery.complete(Unit)
                runCurrent()
                sourceOfTruth.publishExternal("authoritative-row")
                val authoritative = assertIs<StoreResult.Data<String>>(second.awaitItem())
                assertEquals("authoritative-row", authoritative.value)
                assertEquals(Origin.SOT, authoritative.origin)
                second.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            sourceOfTruth.releaseLiveDelivery.complete(Unit)
        }
    }

    @Test
    fun sameGenerationAbsentReplay_afterResidenceAdvances_neverEmitsFalseLoading() = runTest {
        val sourceOfTruth = GraceReplaySourceOfTruth(initial = null)
        val engine = newEngine(sourceOfTruth) { "fresh" }

        try {
            app.cash.turbine.turbineScope {
                val first = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
                assertIs<StoreError.Missing>(
                    assertIs<StoreResult.Error>(first.awaitItem()).error,
                )
                sourceOfTruth.liveReaderStarted.await()
                runCurrent()
                first.cancelAndIgnoreRemainingEvents()
                advanceToLastReaderGraceMillisecond()

                sourceOfTruth.gateNextLiveDelivery()
                val refresh =
                    backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                        engine.get(Freshness.MustBeFresh)
                    }
                sourceOfTruth.liveDeliveryBlocked.await()
                assertEquals("fresh", refresh.await())

                val second = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("fresh", assertIs<StoreResult.Data<String>>(second.awaitItem()).value)
                runCurrent()
                second.expectNoEvents()

                sourceOfTruth.releaseLiveDelivery.complete(Unit)
                runCurrent()
                sourceOfTruth.publishExternal("authoritative-after-absent")
                val authoritative = assertIs<StoreResult.Data<String>>(second.awaitItem())
                assertEquals("authoritative-after-absent", authoritative.value)
                assertEquals(Origin.SOT, authoritative.origin)
                second.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            sourceOfTruth.releaseLiveDelivery.complete(Unit)
        }
    }

    @Test
    fun queuedAbsent_consumesTag_andLaterEqualRowDoesNotInheritFetcher() = runTest {
        val sourceOfTruth = QueuedAbsentThenWriterSourceOfTruth()
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "candidate" }
        }

        try {
            app.cash.turbine.turbineScope {
                val observer = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(observer.awaitItem()).value)
                sourceOfTruth.liveReaderStarted.await()
                runCurrent()

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
                val writer = assertIs<StoreResult.Data<String>>(observer.awaitItem())
                assertEquals("candidate", writer.value)
                assertEquals(Origin.FETCHER, writer.origin)

                sourceOfTruth.publishExternal(null)
                assertIs<StoreResult.Loading>(observer.awaitItem())
                assertIs<StoreError.Missing>(
                    assertIs<StoreResult.Error>(observer.awaitItem()).error,
                )
                sourceOfTruth.publishExternal("candidate")
                val external = assertIs<StoreResult.Data<String>>(observer.awaitItem())
                assertEquals("candidate", external.value)
                assertEquals(Origin.SOT, external.origin)
                observer.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            sourceOfTruth.releaseWriterEcho.complete(Unit)
            store.close()
        }
    }

    @Test
    fun mustBeFresh_externalWithheldRow_transitionsDataToLoadingBeforeRefresh() = runTest {
        val sourceOfTruth = ReactiveSourceOfTruth(initial = null)
        val secondFetchStarted = CompletableDeferred<Unit>()
        val releaseSecondFetch = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher {
                when (++fetchCalls) {
                    1 -> "v1"
                    2 -> {
                        secondFetchStarted.complete(Unit)
                        releaseSecondFetch.await()
                        "v2"
                    }

                    else -> error("unexpected fetch call $fetchCalls")
                }
            }
        }

        try {
            app.cash.turbine.turbineScope {
                val collector =
                    store.stream(key, Freshness.MustBeFresh).testIn(backgroundScope)
                assertIs<StoreResult.Loading>(collector.awaitItem())
                assertEquals("v1", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
                sourceOfTruth.liveReaderStarted.await()
                runCurrent()

                sourceOfTruth.publishExternal("external")
                secondFetchStarted.await()
                assertIs<StoreResult.Loading>(collector.awaitItem())

                releaseSecondFetch.complete(Unit)
                val refreshed = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("v2", refreshed.value)
                assertEquals(Origin.FETCHER, refreshed.origin)
                assertEquals(2, fetchCalls)
                collector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseSecondFetch.complete(Unit)
            store.close()
        }
    }

    @Test
    fun maxAge_externalWithheldRow_transitionsDataToLoadingBeforeRefresh() = runTest {
        val sourceOfTruth = ReactiveSourceOfTruth(initial = null)
        val secondFetchStarted = CompletableDeferred<Unit>()
        val releaseSecondFetch = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher {
                when (++fetchCalls) {
                    1 -> "v1"
                    2 -> {
                        secondFetchStarted.complete(Unit)
                        releaseSecondFetch.await()
                        "v2"
                    }

                    else -> error("unexpected fetch call $fetchCalls")
                }
            }
        }

        try {
            app.cash.turbine.turbineScope {
                val collector =
                    store.stream(key, Freshness.MaxAge(notOlderThan = 5.minutes))
                        .testIn(backgroundScope)
                assertIs<StoreResult.Loading>(collector.awaitItem())
                assertEquals("v1", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
                sourceOfTruth.liveReaderStarted.await()
                runCurrent()

                sourceOfTruth.publishExternal("external")
                secondFetchStarted.await()
                assertIs<StoreResult.Loading>(collector.awaitItem())

                releaseSecondFetch.complete(Unit)
                val refreshed = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("v2", refreshed.value)
                assertEquals(Origin.FETCHER, refreshed.origin)
                assertEquals(2, fetchCalls)
                collector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseSecondFetch.complete(Unit)
            store.close()
        }
    }

    /** Keeps the shared reader alive through grace while allowing direct one-shot RYW probes. */
    private class GraceReplaySourceOfTruth(
        initial: String?,
    ) : SingleRowTestSourceOfTruth<String> {
        private val liveRows = MutableSharedFlow<String?>(extraBufferCapacity = 8)
        private var current: String? = initial
        private var gateNextLive = false

        val liveReaderStarted = CompletableDeferred<Unit>()
        var liveDeliveryBlocked = CompletableDeferred<Unit>()
            private set
        var releaseLiveDelivery = CompletableDeferred<Unit>()
            private set

        fun gateNextLiveDelivery() {
            check(liveReaderStarted.isCompleted) {
                "the shared reader must be active before gating delivery"
            }
            check(!gateNextLive) { "a live delivery is already gated" }
            gateNextLive = true
            liveDeliveryBlocked = CompletableDeferred()
            releaseLiveDelivery = CompletableDeferred()
        }

        override fun reader(key: TestKey): Flow<String?> =
            flow {
                // `first()` aborts during this emit, so only the long-lived pipeline reaches the
                // live tail and marks itself ready. Every full collection still remains live.
                emit(current)
                liveReaderStarted.complete(Unit)
                liveRows.collect { row ->
                    if (gateNextLive) {
                        gateNextLive = false
                        liveDeliveryBlocked.complete(Unit)
                        releaseLiveDelivery.await()
                    }
                    emit(row)
                }
            }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            current = value
            liveRows.emit(value)
        }

        override suspend fun delete(key: TestKey) {
            current = null
            liveRows.emit(null)
        }

        suspend fun publishExternal(value: String) {
            current = value
            liveRows.emit(value)
        }
    }

    private fun TestScope.newEngine(
        sourceOfTruth: SourceOfTruth<TestKey, String>,
        fetcher: suspend () -> String,
    ): KeyEngine<TestKey, String> =
        KeyEngine(
            key = key,
            keyId = KeyId.from(key),
            fetcher = LambdaFetcher { fetcher() },
            sot = sourceOfTruth,
            bookkeeper = InMemoryBookkeeper(),
            validator = DefaultFreshnessValidator,
            wallClock = FakeWallClock(now = 0L),
            engineScope = backgroundScope,
        )

    /** Arms the 100ms stop timer, then stays one virtual millisecond inside its grace window. */
    private fun TestScope.advanceToLastReaderGraceMillisecond() {
        runCurrent()
        advanceTimeBy(READER_PIPELINE_GRACE_MILLIS - 1L)
    }

    private class QueuedAbsentThenWriterSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableSharedFlow<String?>(replay = 1)
        private var readerCalls = 0
        val liveReaderStarted = CompletableDeferred<Unit>()
        val queuedAbsentEmitted = CompletableDeferred<Unit>()
        val releaseWriterEcho = CompletableDeferred<Unit>()

        init {
            check(rows.tryEmit("seed"))
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

        suspend fun publishExternal(value: String?) {
            rows.emit(value)
        }
    }

    private class ReactiveSourceOfTruth(
        initial: String?,
    ) : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>(initial)
        private var readerCalls = 0
        val liveReaderStarted = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            if (readerCalls >= 2) liveReaderStarted.complete(Unit)
            return rows
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

        fun publishExternal(value: String) {
            rows.value = value
        }
    }
}
