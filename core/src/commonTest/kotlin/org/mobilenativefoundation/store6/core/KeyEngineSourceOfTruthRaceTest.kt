package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.internal.DefaultFreshnessValidator
import org.mobilenativefoundation.store6.core.internal.FetchDisposition
import org.mobilenativefoundation.store6.core.internal.FetchOutcome
import org.mobilenativefoundation.store6.core.internal.FetchSlot
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)
class KeyEngineSourceOfTruthRaceTest {
    @Test
    fun nullMetaHydration_servesLocalOnlyAndStartsOneCachedOrFetchRevalidation() = runTest {
        val sourceOfTruth = InMemorySourceOfTruth<TestKey, String>()
        val key = TestKey("key")
        sourceOfTruth.write(key, "durable")
        val fetched = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher {
                fetchCalls += 1
                fetched.complete(Unit)
                "fresh"
            }
        }

        try {
            assertEquals("durable", store.get(key, Freshness.LocalOnly))
            assertEquals("durable", store.get(key, Freshness.CachedOrFetch))
            fetched.await()
            sourceOfTruth.reader(key).filter { it == "fresh" }.first()
            runCurrent()
            assertEquals(1, fetchCalls)
            assertEquals("fresh", store.get(key, Freshness.LocalOnly))
        } finally {
            store.close()
        }
    }

    @Test
    fun readerFactoryFailure_isTypedOnceForAnOutageAndRecovers() = runTest {
        val sourceOfTruth = FailingReaderSourceOfTruth()
        val key = TestKey("key")
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "seed" }
        }

        try {
            assertEquals("seed", store.get(key))
            sourceOfTruth.beginFactoryOutage(failures = 3)

            store.stream(key, Freshness.LocalOnly).test {
                assertEquals("seed", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                val failure = assertIs<StoreResult.Error>(awaitItem())
                assertIs<StoreError.Persistence>(failure.error)

                withContext(Dispatchers.Default) {
                    sourceOfTruth.recovered.await()
                }
                assertTrue(sourceOfTruth.readerCalls >= 4)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun readerCollectionFailure_isTypedOnceForAContiguousOutageAndRecovers() = runTest {
        val sourceOfTruth = FailingReaderSourceOfTruth()
        val key = TestKey("key")
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "seed" }
        }

        try {
            assertEquals("seed", store.get(key))
            sourceOfTruth.beginCollectionOutage(failures = 3, emitBeforeFirstFailure = true)

            store.stream(key, Freshness.LocalOnly).test {
                assertEquals("seed", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                val failure = assertIs<StoreResult.Error>(awaitItem())
                assertIs<StoreError.Persistence>(failure.error)

                withContext(Dispatchers.Default) {
                    sourceOfTruth.recovered.await()
                }
                assertTrue(sourceOfTruth.readerCalls >= 4)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun twoCollectorsShareOneReaderRetryLoop() = runTest {
        val sourceOfTruth = FailingReaderSourceOfTruth()
        val key = TestKey("key")
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "seed" }
        }

        try {
            assertEquals("seed", store.get(key))
            sourceOfTruth.beginFactoryOutage(failures = 1)

            app.cash.turbine.turbineScope {
                val first = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                val second = store.stream(key, Freshness.LocalOnly).testIn(backgroundScope)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(first.awaitItem()).value)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(second.awaitItem()).value)
                assertIs<StoreError.Persistence>(
                    assertIs<StoreResult.Error>(first.awaitItem()).error,
                )
                assertIs<StoreError.Persistence>(
                    assertIs<StoreResult.Error>(second.awaitItem()).error,
                )
                withContext(Dispatchers.Default) {
                    sourceOfTruth.recovered.await()
                }
                assertEquals(2, sourceOfTruth.readerCalls)
                first.cancelAndIgnoreRemainingEvents()
                second.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun hydrationReaderFailure_isTypedPersistenceForGetAndRecoversOnNextRead() = runTest {
        val boom = IllegalStateException("reader failed")
        val sourceOfTruth = AdapterFailureSourceOfTruth(readerFailure = boom)
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "unused" }
        }

        try {
            val failure =
                assertFailsWith<StoreException> {
                    store.get(TestKey("key"), Freshness.LocalOnly)
                }
            val persistence = assertIs<StoreError.Persistence>(failure.error)
            assertTrue(persistence.cause === boom)
            assertTrue(persistence.message.contains("Durable data could not be observed"))

            sourceOfTruth.recoverReaderWith("durable")
            assertEquals("durable", store.get(TestKey("key"), Freshness.LocalOnly))
        } finally {
            store.close()
        }
    }

    @Test
    fun persistenceWriteFailure_revokesTag_reportsTypedFailure_andLaterRowIsSot() = runTest {
        val boom = IllegalStateException("write rejected")
        val sourceOfTruth = GatedFailingWriteSourceOfTruth(failure = boom)
        val bookkeeper = RecordingBookkeeper()
        val clock = FakeWallClock(now = 100L)
        val key = TestKey("key")
        val engine =
            engine(
                key = key,
                sourceOfTruth = sourceOfTruth,
                bookkeeper = bookkeeper,
                clock = clock,
            ) { FetcherResult.Success("fetched") }

        val read =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.get(Freshness.CachedOrFetch) }
            }
        val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
        runCurrent()
        sourceOfTruth.writeStarted.await()
        assertNotNull(engine.state.value.attribution)

        clock.now = 125L
        sourceOfTruth.releaseWrite.complete(Unit)
        val failure = assertIs<StoreException>(read.await().exceptionOrNull())
        val persistence = assertIs<StoreError.Persistence>(failure.error)
        assertTrue(persistence.cause === boom)
        assertTrue(persistence.message.contains("source of truth rejected the write"))

        assertNull(engine.state.value.attribution)
        assertEquals(FetchDisposition.Failed, ticket.disposition.value)
        val outcome = assertIs<FetchOutcome.Failed>(ticket.outcome.await())
        assertEquals(125L, outcome.atEpochMillis)
        assertTrue(outcome.bookkeepingRecorded)
        assertIs<StoreError.Persistence>(outcome.exception.error)
        assertEquals(
            listOf<BookkeepingEvent>(
                BookkeepingEvent.Failure(KeyId.from(key), atEpochMillis = 125L),
            ),
            bookkeeper.events,
        )
        val status = assertNotNull(bookkeeper.status(key))
        assertEquals(125L, status.lastFailureAtEpochMillis)
        assertEquals(1, status.consecutiveFailures)
        assertNull(status.meta)

        engine.stream(Freshness.LocalOnly).test {
            sourceOfTruth.publishExternal("external")
            var external: StoreResult.Data<String>? = null
            while (external == null) {
                when (val item = awaitItem()) {
                    is StoreResult.Data -> external = item
                    is StoreResult.Error -> assertIs<StoreError.Missing>(item.error)
                    is StoreResult.Loading -> Unit
                    is StoreResult.Revalidated -> error("LocalOnly must not revalidate")
                }
            }
            assertEquals("external", external.value)
            assertEquals(Origin.SOT, external.origin)
            assertTrue(external.isStale)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun persistenceWriteFailure_queuedClear_forgetWinsAndNoFailureResurfaces() = runTest {
        val boom = IllegalStateException("write rejected")
        val sourceOfTruth = GatedFailingWriteSourceOfTruth(failure = boom)
        val bookkeeper = RecordingBookkeeper()
        val clock = FakeWallClock(now = 200L)
        val key = TestKey("key")
        val engine =
            engine(
                key = key,
                sourceOfTruth = sourceOfTruth,
                bookkeeper = bookkeeper,
                clock = clock,
            ) { FetcherResult.Success("fetched") }

        val read =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.get(Freshness.CachedOrFetch) }
            }
        val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
        runCurrent()
        sourceOfTruth.writeStarted.await()
        assertNotNull(engine.state.value.attribution)
        val clear =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.clear()
            }
        runCurrent()
        assertEquals(0, sourceOfTruth.deleteCalls)

        clock.now = 225L
        sourceOfTruth.releaseWrite.complete(Unit)
        val failure = assertIs<StoreException>(read.await().exceptionOrNull())
        val persistence = assertIs<StoreError.Persistence>(failure.error)
        assertTrue(persistence.cause === boom)
        clear.await()
        runCurrent()

        val outcome = assertIs<FetchOutcome.Failed>(ticket.outcome.await())
        assertEquals(225L, outcome.atEpochMillis)
        assertTrue(outcome.bookkeepingRecorded)
        assertEquals(FetchDisposition.Failed, ticket.disposition.value)
        assertEquals(
            listOf(
                BookkeepingEvent.Failure(KeyId.from(key), atEpochMillis = 225L),
                BookkeepingEvent.Forget(KeyId.from(key)),
            ),
            bookkeeper.events,
        )
        assertNull(bookkeeper.status(key))
        assertEquals(1, sourceOfTruth.deleteCalls)
        assertNull(sourceOfTruth.current)
        val state = engine.state.value
        assertEquals(1L, state.staleEpoch)
        assertEquals(1L, state.clearEpoch)
        assertEquals(1L, state.readerGen)
        assertNull(state.attribution)
        assertEquals(FetchSlot.Idle, state.fetch)

        val missing =
            assertFailsWith<StoreException> {
                engine.get(Freshness.LocalOnly)
            }
        assertIs<StoreError.Missing>(missing.error)
        runCurrent()
        assertEquals(2, bookkeeper.events.size)
    }

    @Test
    fun clearDeleteFailure_preservesResidenceEpochsAndBookkeeping() = runTest {
        val boom = IllegalStateException("delete rejected")
        val sourceOfTruth = AdapterFailureSourceOfTruth()
        val bookkeeper = RecordingBookkeeper()
        val clock = FakeWallClock(now = 300L)
        val key = TestKey("key")
        val engine =
            engine(
                key = key,
                sourceOfTruth = sourceOfTruth,
                bookkeeper = bookkeeper,
                clock = clock,
            ) { FetcherResult.Success("seed", etag = "seed-etag") }

        assertEquals("seed", engine.get(Freshness.CachedOrFetch))
        engine.invalidate()
        val stateBefore = engine.state.value
        val statusBefore = assertNotNull(bookkeeper.status(key))
        val eventsBefore = bookkeeper.events.toList()
        assertEquals("seed", sourceOfTruth.current)
        sourceOfTruth.deleteFailure = boom

        val failure = assertFailsWith<StoreException> { engine.clear() }
        val persistence = assertIs<StoreError.Persistence>(failure.error)
        assertTrue(persistence.cause === boom)
        assertTrue(persistence.message.contains("retry clear()"))

        val stateAfter = engine.state.value
        assertEquals(stateBefore.staleEpoch, stateAfter.staleEpoch)
        assertEquals(stateBefore.clearEpoch, stateAfter.clearEpoch)
        assertEquals(stateBefore.readerGen, stateAfter.readerGen)
        assertEquals(stateBefore.fetch, stateAfter.fetch)
        assertTrue(stateAfter.attribution === stateBefore.attribution)
        assertTrue(bookkeeper.status(key) === statusBefore)
        assertEquals(eventsBefore, bookkeeper.events)
        assertEquals(1, sourceOfTruth.deleteCalls)
        assertEquals("seed", sourceOfTruth.current)
        assertEquals("seed", engine.get(Freshness.LocalOnly))
    }

    @Test
    fun successfulReturnConvergesFinalWriterOverMutationEraIntermediate() = runTest {
        val sourceOfTruth = GatedWriteSourceOfTruth()
        val key = TestKey("key")
        val store = store<TestKey, String> {
            persistence(sourceOfTruth)
            fetcher { "fetched" }
        }

        try {
            assertEquals("seed", store.get(key, Freshness.LocalOnly))
            app.cash.turbine.turbineScope {
                val collector = store.stream(key).testIn(backgroundScope)
                assertEquals("seed", assertIs<StoreResult.Data<String>>(collector.awaitItem()).value)
                sourceOfTruth.liveReaderStarted.await()
                withContext(Dispatchers.Default) {
                    sourceOfTruth.writeStarted.await()
                }

                sourceOfTruth.publishExternal("external")
                runCurrent()
                collector.expectNoEvents()
                sourceOfTruth.releaseWrite.complete(Unit)
                runCurrent()
                val committed = assertIs<StoreResult.Data<String>>(collector.awaitItem())
                assertEquals("fetched", committed.value)
                assertEquals(Origin.FETCHER, committed.origin)
                assertEquals("fetched", store.get(key, Freshness.LocalOnly))
                collector.cancelAndIgnoreRemainingEvents()
            }
        } finally {
            sourceOfTruth.releaseWrite.complete(Unit)
            store.close()
        }
    }

    private fun TestScope.engine(
        key: TestKey,
        sourceOfTruth: SourceOfTruth<TestKey, String>,
        bookkeeper: Bookkeeper,
        clock: FakeWallClock,
        fetcher: suspend (TestKey) -> FetcherResult<String>,
    ): KeyEngine<TestKey, String> =
        KeyEngine(
            key = key,
            keyId = KeyId.from(key),
            fetcher = ResultFetcher(fetcher),
            sot = sourceOfTruth,
            bookkeeper = bookkeeper,
            validator = DefaultFreshnessValidator,
            wallClock = clock,
            engineScope = backgroundScope,
        )

    private sealed interface BookkeepingEvent {
        data class Success(
            val key: KeyId,
            val atEpochMillis: Long,
        ) : BookkeepingEvent

        data class Failure(
            val key: KeyId,
            val atEpochMillis: Long,
        ) : BookkeepingEvent

        data class Forget(
            val key: KeyId,
        ) : BookkeepingEvent
    }

    private class RecordingBookkeeper : Bookkeeper {
        private val delegate = InMemoryBookkeeper()
        val events = mutableListOf<BookkeepingEvent>()

        override suspend fun recordSuccess(
            key: StoreKey,
            meta: StoreMeta,
        ) {
            events += BookkeepingEvent.Success(KeyId.from(key), meta.writtenAtEpochMillis)
            delegate.recordSuccess(key, meta)
        }

        override suspend fun recordFailure(
            key: StoreKey,
            atEpochMillis: Long,
        ) {
            events += BookkeepingEvent.Failure(KeyId.from(key), atEpochMillis)
            delegate.recordFailure(key, atEpochMillis)
        }

        override suspend fun status(key: StoreKey): KeyStatus? = delegate.status(key)

        override suspend fun forget(key: StoreKey) {
            events += BookkeepingEvent.Forget(KeyId.from(key))
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

    private class FailingReaderSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>(null)
        private var factoryFailuresRemaining: Int = 0
        private var collectionFailuresRemaining: Int = 0
        private var emitBeforeFirstCollectionFailure: Boolean = false
        private var trackingRecovery: Boolean = false
        var recovered: CompletableDeferred<Unit> = CompletableDeferred()
            private set
        var readerCalls: Int = 0

        fun beginFactoryOutage(failures: Int) {
            factoryFailuresRemaining = failures
            trackingRecovery = true
            recovered = CompletableDeferred()
            readerCalls = 0
        }

        fun beginCollectionOutage(
            failures: Int,
            emitBeforeFirstFailure: Boolean,
        ) {
            collectionFailuresRemaining = failures
            emitBeforeFirstCollectionFailure = emitBeforeFirstFailure
            trackingRecovery = true
            recovered = CompletableDeferred()
            readerCalls = 0
        }

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            if (factoryFailuresRemaining > 0) {
                factoryFailuresRemaining -= 1
                throw IllegalStateException("reader factory failed")
            }
            val shouldFail = collectionFailuresRemaining > 0
            val emitFirst = emitBeforeFirstCollectionFailure && collectionFailuresRemaining == 3
            if (shouldFail) collectionFailuresRemaining -= 1
            if (!shouldFail && trackingRecovery) {
                recovered.complete(Unit)
                trackingRecovery = false
            }
            return flow {
                if (emitFirst) emit(rows.value)
                if (shouldFail) throw IllegalStateException("reader collection failed")
                rows.collect { emit(it) }
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

    private class GatedWriteSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>("seed")
        private var readerCalls = 0
        val liveReaderStarted = CompletableDeferred<Unit>()
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls += 1
            if (readerCalls >= 2) liveReaderStarted.complete(Unit)
            return flow {
                rows.collect { row ->
                    emit(row)
                }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.value = value
            writeStarted.complete(Unit)
            releaseWrite.await()
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            rows.value = null
        }

        fun publishExternal(value: String) {
            rows.value = value
        }
    }

    private class GatedFailingWriteSourceOfTruth(
        private val failure: Throwable,
    ) : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>(null)
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var deleteCalls: Int = 0
            private set
        val current: String?
            get() = rows.value

        override fun reader(key: TestKey): Flow<String?> = rows

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            writeStarted.complete(Unit)
            releaseWrite.await()
            throw failure
        }

        override suspend fun delete(key: TestKey) {
            deleteCalls += 1
            rows.value = null
        }

        fun publishExternal(value: String) {
            rows.value = value
        }
    }

    private class AdapterFailureSourceOfTruth(
        private var readerFailure: Throwable? = null,
        private var writeFailure: Throwable? = null,
        var deleteFailure: Throwable? = null,
    ) : SingleRowTestSourceOfTruth<String> {
        private val rows = MutableStateFlow<String?>(null)
        var deleteCalls: Int = 0
            private set
        val current: String?
            get() = rows.value

        override fun reader(key: TestKey): Flow<String?> {
            readerFailure?.let { throw it }
            return rows
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            writeFailure?.let { throw it }
            rows.value = value
        }

        override suspend fun delete(key: TestKey) {
            deleteCalls += 1
            deleteFailure?.let { throw it }
            rows.value = null
        }

        fun recoverReaderWith(value: String) {
            rows.value = value
            readerFailure = null
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
