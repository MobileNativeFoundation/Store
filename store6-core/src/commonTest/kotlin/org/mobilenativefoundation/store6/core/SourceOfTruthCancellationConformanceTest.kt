package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.internal.DefaultFreshnessValidator
import org.mobilenativefoundation.store6.core.internal.EngineStoreMeta
import org.mobilenativefoundation.store6.core.internal.FetchDisposition
import org.mobilenativefoundation.store6.core.internal.FetchSlot
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.KeyEngine
import org.mobilenativefoundation.store6.core.internal.KeyId
import org.mobilenativefoundation.store6.core.internal.ResultFetcher
import org.mobilenativefoundation.store6.core.internal.KeyState
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)
class SourceOfTruthCancellationConformanceTest {

    @Test
    fun writeCancellation_revokesTag_cancelsTicket_andMutatesNothing() = runTest {
        val key = TestKey("write-cancellation")
        val sot = ThrowingWriteSourceOfTruth()
        val bookkeeper = InMemoryBookkeeper()
        val engine = engine(key, sot, bookkeeper, backgroundScope) { FetcherResult.Success("value") }

        val read =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.get(Freshness.CachedOrFetch) }
            }
        sot.writeStarted.await()
        val tag = assertNotNull(engine.state.value.attribution)
        val ticket = tag.owner
        assertEquals(FetchDisposition.Committing::class, ticket.disposition.value::class)

        sot.releaseCancellation.complete(Unit)
        val failure = read.await().exceptionOrNull()

        assertIs<CancellationException>(failure)
        assertTrue(ticket.outcome.isCancelled)
        assertEquals(FetchDisposition.Cancelled, ticket.disposition.value)
        assertEquals(KeyState.Initial, engine.state.value)
        assertNull(sot.current)
        assertNull(bookkeeper.status(key))

        sot.publishExternal("value")
        engine.stream(Freshness.LocalOnly).test {
            val external = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("value", external.value)
            assertEquals(Origin.SOT, external.origin)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun writeCancellation_terminatesOwningStream_andEngineRemainsReusable() = runTest {
        val key = TestKey("write-cancellation-stream")
        val sot = ThrowingWriteSourceOfTruth()
        val bookkeeper = InMemoryBookkeeper()
        val engine = engine(key, sot, bookkeeper, backgroundScope) { FetcherResult.Success("value") }
        val collector =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.stream(Freshness.CachedOrFetch).collect() }
            }

        sot.writeStarted.await()
        val ticket = assertNotNull(engine.state.value.attribution).owner
        sot.releaseCancellation.complete(Unit)

        val failure =
            withContext(Dispatchers.Default) {
                collector.await()
            }.exceptionOrNull()
        assertIs<CancellationException>(failure)
        assertEquals("write cancelled", failure.message)
        assertTrue(ticket.outcome.isCancelled)
        assertEquals(FetchDisposition.Cancelled, ticket.disposition.value)
        assertEquals(KeyState.Initial, engine.state.value)
        assertNull(sot.current)
        assertNull(bookkeeper.status(key))

        sot.publishExternal("external")
        engine.stream(Freshness.LocalOnly).test {
            val external = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("external", external.value)
            assertEquals(Origin.SOT, external.origin)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun clearDeleteCancellation_preservesState() = runTest {
        val key = TestKey("clear-delete-cancellation")
        val sot = ThrowingDeleteSourceOfTruth(initial = "seed")
        val bookkeeper = InMemoryBookkeeper()
        val meta = EngineStoreMeta(writtenAtEpochMillis = 10L, etag = "seed")
        bookkeeper.recordSuccess(key, meta)
        val engine = engine(key, sot, bookkeeper, backgroundScope) { error("fetch must not run") }

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        engine.invalidate()
        val stateBefore = engine.state.value
        val statusBefore = assertNotNull(bookkeeper.status(key))
        val clear =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.clear() }
            }
        sot.deleteStarted.await()

        sot.releaseCancellation.complete(Unit)
        val failure = clear.await().exceptionOrNull()

        assertIs<CancellationException>(failure)
        assertEquals(stateBefore, engine.state.value)
        assertEquals("seed", sot.current)
        assertEquals("seed", engine.get(Freshness.LocalOnly))
        assertTrue(bookkeeper.status(key) === statusBefore)
        assertEquals(1, sot.deleteCalls)
    }

    @Test
    fun serverDeleteCancellation_preservesStateAndCancelsTicket() = runTest {
        val key = TestKey("server-delete-cancellation")
        val sot = ThrowingDeleteSourceOfTruth(initial = "seed")
        val bookkeeper = InMemoryBookkeeper()
        val meta = EngineStoreMeta(writtenAtEpochMillis = 20L, etag = "seed")
        bookkeeper.recordSuccess(key, meta)
        val engine = engine(key, sot, bookkeeper, backgroundScope) { FetcherResult.Deleted }

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        engine.invalidate()
        val stateBefore = engine.state.value
        val statusBefore = assertNotNull(bookkeeper.status(key))
        val read =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.get(Freshness.MustBeFresh) }
            }
        sot.deleteStarted.await()
        val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket

        sot.releaseCancellation.complete(Unit)
        val failure = read.await().exceptionOrNull()

        assertIs<CancellationException>(failure)
        assertTrue(ticket.outcome.isCancelled)
        assertEquals(stateBefore, engine.state.value)
        assertEquals("seed", sot.current)
        assertEquals("seed", engine.get(Freshness.LocalOnly))
        assertTrue(bookkeeper.status(key) === statusBefore)
        assertEquals(1, sot.deleteCalls)
    }

    @Test
    fun serverDeleteCancellation_terminatesDynamicWatcher_andPreservesResident() = runTest {
        val key = TestKey("server-delete-cancellation-stream")
        val sot = ThrowingDeleteSourceOfTruth(initial = null)
        val bookkeeper = InMemoryBookkeeper()
        var fetchCalls = 0
        val engine =
            engine(key, sot, bookkeeper, backgroundScope) {
                when (++fetchCalls) {
                    1 -> FetcherResult.Success("seed")
                    2 -> FetcherResult.Deleted
                    else -> error("unexpected fetch call $fetchCalls")
                }
            }

        assertEquals("seed", engine.get(Freshness.CachedOrFetch))
        val statusBeforeInvalidation = assertNotNull(bookkeeper.status(key))
        val initialData = CompletableDeferred<Unit>()
        val collector =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    engine.stream(Freshness.CachedOrFetch).collect { result ->
                        if (result is StoreResult.Data && result.value == "seed") {
                            initialData.complete(Unit)
                        }
                    }
                }
            }
        initialData.await()

        engine.invalidate()
        val statusAfterInvalidation = assertNotNull(bookkeeper.status(key))
        assertTrue(statusAfterInvalidation.durablyStale)
        assertEquals(statusBeforeInvalidation.meta, statusAfterInvalidation.meta)
        sot.deleteStarted.await()
        val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket
        sot.releaseCancellation.complete(Unit)

        val failure =
            withContext(Dispatchers.Default) {
                collector.await()
            }.exceptionOrNull()
        assertIs<CancellationException>(failure)
        assertEquals("delete cancelled", failure.message)
        assertTrue(ticket.outcome.isCancelled)
        assertEquals("seed", sot.current)
        assertTrue(bookkeeper.status(key) === statusAfterInvalidation)
        assertEquals(2, fetchCalls)

        engine.stream(Freshness.LocalOnly).test {
            val resident = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("seed", resident.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun commitFetch_cancelAfterDurableWrite_completesBookkeepingAndResidenceAtom() = runTest {
        val key = TestKey("post-write-cancellation")
        val sot = PostWriteReturnSourceOfTruth()
        val bookkeeper = SignallingBookkeeper()
        val engineJob = SupervisorJob()
        val engineScope = CoroutineScope(coroutineContext + engineJob)
        val engine = engine(key, sot, bookkeeper, engineScope) { FetcherResult.Success("durable") }

        try {
            val read =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { engine.get(Freshness.CachedOrFetch) }
                }
            sot.writeApplied.await()
            val ticket = assertNotNull(engine.state.value.attribution).owner

            engineJob.cancel(CancellationException("cancel after durable write"))
            sot.releaseReturn.complete(Unit)
            bookkeeper.successRecorded.await()
            runCurrent()

            assertIs<CancellationException>(read.await().exceptionOrNull())
            assertEquals("durable", sot.current)
            assertNotNull(bookkeeper.status(key)?.meta)
            assertIs<FetchSlot.Idle>(engine.state.value.fetch)
            assertNull(engine.state.value.attribution)
            assertIs<FetchDisposition.Committed>(ticket.disposition.value)
            assertTrue(ticket.outcome.isCancelled)
        } finally {
            sot.releaseReturn.complete(Unit)
            engineJob.cancel()
        }
    }

    @Test
    fun clear_cancelBeforeWriteLock_performsNoDelete() = runTest {
        val key = TestKey("clear-before-write-lock")
        val sot = GatedSuccessfulDeleteSourceOfTruth(initial = "seed")
        val bookkeeper = InMemoryBookkeeper()
        val engine = engine(key, sot, bookkeeper, backgroundScope) { error("fetch must not run") }

        assertEquals("seed", engine.get(Freshness.LocalOnly))
        val first =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.clear()
            }
        sot.deleteStarted.await()
        val second =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.clear()
            }
        runCurrent()

        second.cancel(CancellationException("cancel before writeLock"))
        sot.releaseDelete.complete(Unit)
        first.await()

        assertIs<CancellationException>(runCatching { second.await() }.exceptionOrNull())
        assertEquals(1, sot.deleteCalls)
        assertNull(sot.current)
        assertEquals(1L, engine.state.value.clearEpoch)
        assertEquals(1L, engine.state.value.readerGen)
    }

    @Test
    fun commitDeleted_cancelAfterDurableDelete_completesStateAndBookkeepingAtomically() = runTest {
        val key = TestKey("post-delete-cancellation")
        val sot = PostDeleteReturnSourceOfTruth(initial = "seed")
        val bookkeeper = SignallingBookkeeper()
        bookkeeper.recordSuccess(
            key,
            EngineStoreMeta(writtenAtEpochMillis = 30L, etag = "seed"),
        )
        val engineJob = SupervisorJob()
        val engineScope = CoroutineScope(coroutineContext + engineJob)
        val engine = engine(key, sot, bookkeeper, engineScope) { FetcherResult.Deleted }

        try {
            assertEquals("seed", engine.get(Freshness.LocalOnly))
            engine.invalidate()
            val stateBefore = engine.state.value
            val read =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { engine.get(Freshness.MustBeFresh) }
                }
            sot.deleteApplied.await()
            val ticket = assertIs<FetchSlot.InFlight>(engine.state.value.fetch).ticket

            engineJob.cancel(CancellationException("cancel after durable delete"))
            sot.releaseReturn.complete(Unit)
            bookkeeper.forgotten.await()
            runCurrent()

            assertIs<CancellationException>(read.await().exceptionOrNull())
            assertNull(sot.current)
            assertNull(bookkeeper.status(key))
            assertIs<FetchSlot.Idle>(engine.state.value.fetch)
            assertEquals(stateBefore.clearEpoch + 1L, engine.state.value.clearEpoch)
            assertEquals(stateBefore.readerGen + 1L, engine.state.value.readerGen)
            assertEquals(stateBefore.staleEpoch, engine.state.value.staleEpoch)
            assertNull(engine.state.value.attribution)
            assertEquals(FetchDisposition.Deleted, ticket.disposition.value)
            assertTrue(ticket.outcome.isCancelled)
        } finally {
            sot.releaseReturn.complete(Unit)
            engineJob.cancel()
        }
    }

    private fun engine(
        key: TestKey,
        sot: SourceOfTruth<TestKey, String>,
        bookkeeper: Bookkeeper,
        scope: CoroutineScope,
        fetcher: suspend (TestKey) -> FetcherResult<String>,
    ): KeyEngine<TestKey, String> =
        KeyEngine(
            key = key,
            keyId = KeyId.from(key),
            fetcher = ResultFetcher(fetcher),
            sot = sot,
            bookkeeper = bookkeeper,
            validator = DefaultFreshnessValidator,
            wallClock = FakeWallClock(now = 100L),
            engineScope = scope,
        )

    private class ThrowingWriteSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val row = MutableStateFlow<String?>(null)
        val writeStarted = CompletableDeferred<Unit>()
        val releaseCancellation = CompletableDeferred<Unit>()
        val current: String?
            get() = row.value

        override fun reader(key: TestKey): Flow<String?> = row

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            writeStarted.complete(Unit)
            releaseCancellation.await()
            throw CancellationException("write cancelled")
        }

        override suspend fun delete(key: TestKey) {
            row.value = null
        }

        fun publishExternal(value: String) {
            row.value = value
        }
    }

    private class ThrowingDeleteSourceOfTruth(
        initial: String?,
    ) : SingleRowTestSourceOfTruth<String> {
        private val row = MutableStateFlow(initial)
        val deleteStarted = CompletableDeferred<Unit>()
        val releaseCancellation = CompletableDeferred<Unit>()
        var deleteCalls: Int = 0
            private set
        val current: String?
            get() = row.value

        override fun reader(key: TestKey): Flow<String?> = row

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            row.value = value
        }

        override suspend fun delete(key: TestKey) {
            deleteCalls++
            deleteStarted.complete(Unit)
            releaseCancellation.await()
            throw CancellationException("delete cancelled")
        }
    }

    private class PostWriteReturnSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val row = MutableStateFlow<String?>(null)
        val writeApplied = CompletableDeferred<Unit>()
        val releaseReturn = CompletableDeferred<Unit>()
        val current: String?
            get() = row.value

        override fun reader(key: TestKey): Flow<String?> = row

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            row.value = value
            writeApplied.complete(Unit)
            withContext(NonCancellable) { releaseReturn.await() }
        }

        override suspend fun delete(key: TestKey) {
            row.value = null
        }
    }

    private class GatedSuccessfulDeleteSourceOfTruth(
        initial: String?,
    ) : SingleRowTestSourceOfTruth<String> {
        private val row = MutableStateFlow(initial)
        val deleteStarted = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        var deleteCalls: Int = 0
            private set
        val current: String?
            get() = row.value

        override fun reader(key: TestKey): Flow<String?> = row

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            row.value = value
        }

        override suspend fun delete(key: TestKey) {
            deleteCalls++
            deleteStarted.complete(Unit)
            releaseDelete.await()
            row.value = null
        }
    }

    private class PostDeleteReturnSourceOfTruth(
        initial: String?,
    ) : SingleRowTestSourceOfTruth<String> {
        private val row = MutableStateFlow(initial)
        val deleteApplied = CompletableDeferred<Unit>()
        val releaseReturn = CompletableDeferred<Unit>()
        val current: String?
            get() = row.value

        override fun reader(key: TestKey): Flow<String?> = row

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            row.value = value
        }

        override suspend fun delete(key: TestKey) {
            row.value = null
            deleteApplied.complete(Unit)
            releaseReturn.await()
        }
    }

    private class SignallingBookkeeper : Bookkeeper {
        private val delegate = InMemoryBookkeeper()
        val successRecorded = CompletableDeferred<Unit>()
        val forgotten = CompletableDeferred<Unit>()

        override suspend fun recordSuccess(
            key: StoreKey,
            meta: StoreMeta,
        ) {
            delegate.recordSuccess(key, meta)
            successRecorded.complete(Unit)
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
