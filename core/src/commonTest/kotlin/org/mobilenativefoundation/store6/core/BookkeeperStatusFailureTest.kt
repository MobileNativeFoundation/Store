package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.internal.DefaultFreshnessValidator
import org.mobilenativefoundation.store6.core.internal.EngineStoreMeta
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import org.mobilenativefoundation.store6.core.internal.KeyEngine
import org.mobilenativefoundation.store6.core.internal.KeyId
import org.mobilenativefoundation.store6.core.internal.ResultFetcher
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class, ExperimentalCoroutinesApi::class)
class BookkeeperStatusFailureTest {
    @Test
    fun initialStatusFailure_getThrowsTypedPersistenceWithOriginalCause() = runTest {
        val key = TestKey("status")
        val failure = IllegalStateException("status unavailable")
        val bookkeeper = FailingStatusBookkeeper().also { it.failure = failure }
        val sot = InMemorySourceOfTruth<TestKey, String>().also { it.write(key, "seed") }
        val engine = engine(key, sot, bookkeeper)

        val thrown = assertFailsWith<StoreException> { engine.get(Freshness.LocalOnly) }

        assertSame(failure, assertIs<StoreError.Persistence>(thrown.error).cause)
        assertSame(failure, thrown.cause)
    }

    @Test
    fun initialStatusFailure_streamEmitsPersistenceAndRecoversConservatively() = runTest {
        val key = TestKey("status")
        val failure = IllegalStateException("status unavailable")
        val bookkeeper = FailingStatusBookkeeper()
        bookkeeper.recordSuccess(key, EngineStoreMeta(0L, "seed-etag"))
        bookkeeper.failure = failure
        val sot = InMemorySourceOfTruth<TestKey, String>().also { it.write(key, "seed") }
        val engine = engine(key, sot, bookkeeper)

        engine.stream(Freshness.LocalOnly).test {
            val error = assertIs<StoreResult.Error>(awaitItem())
            assertSame(failure, assertIs<StoreError.Persistence>(error.error).cause)
            bookkeeper.failure = null
            advanceTimeBy(250L)
            runCurrent()
            val recovered = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("seed", recovered.value)
            assertTrue(recovered.isStale)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("fetched", engine.get(Freshness.MustBeFresh))
        engine.stream(Freshness.LocalOnly).test {
            assertTrue(!assertIs<StoreResult.Data<String>>(awaitItem()).isStale)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun activeStatusFailure_streamEmitsPersistenceAndRetainsLaterStaleMark() = runTest {
        val key = TestKey("status")
        val failure = IllegalStateException("active status unavailable")
        val bookkeeper = FailingStatusBookkeeper()
        val sot = InMemorySourceOfTruth<TestKey, String>().also { it.write(key, "seed") }
        val engine = engine(key, sot, bookkeeper)

        engine.stream(Freshness.LocalOnly).test {
            assertEquals("seed", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            runCurrent()
            bookkeeper.markStale(key)
            bookkeeper.failure = failure
            sot.write(key, "updated")
            val error = assertIs<StoreResult.Error>(awaitItem())
            assertSame(failure, assertIs<StoreError.Persistence>(error.error).cause)
            bookkeeper.failure = null
            advanceTimeBy(250L)
            runCurrent()
            val recovered = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("updated", recovered.value)
            assertTrue(recovered.isStale)
            assertTrue(bookkeeper.status(key)!!.durablyStale)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun statusCancellation_getRemainsCancellation() = runTest {
        val key = TestKey("status")
        val cancellation = CancellationException("status cancelled")
        val bookkeeper = FailingStatusBookkeeper().also { it.failure = cancellation }
        val sot = InMemorySourceOfTruth<TestKey, String>()
        val engine = engine(key, sot, bookkeeper)

        assertSame(cancellation, assertFailsWith<CancellationException> {
            engine.get(Freshness.LocalOnly)
        })
        bookkeeper.failure = null
        sot.write(key, "recovered")
        assertEquals("recovered", engine.get(Freshness.LocalOnly))
    }

    @Test
    fun statusVmError_isNotMappedToPersistence() = runTest {
        val key = TestKey("status")
        val fatal = AssertionError("fatal status failure")
        val bookkeeper = FailingStatusBookkeeper().also { it.failure = fatal }
        val engine = engine(key, InMemorySourceOfTruth(), bookkeeper)

        assertSame(fatal, assertFailsWith<AssertionError> { engine.get(Freshness.LocalOnly) })
    }

    @Test
    fun closeDuringStatusRetry_cancelsWithoutAdvancingRetryDelay() = runTest {
        val bookkeeper = FailingStatusBookkeeper().also {
            it.failure = IllegalStateException("status unavailable")
        }
        val store = store<TestKey, String> {
            fetcher { error("unexpected fetch") }
            bookkeeper(bookkeeper)
        }
        val errorReceived = kotlinx.coroutines.CompletableDeferred<Unit>()
        val collector = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            runCatching {
                store.stream(TestKey("status"), Freshness.LocalOnly).collect { result ->
                    if (result is StoreResult.Error) errorReceived.complete(Unit)
                }
            }
        }
        try {
            errorReceived.await()
            runCurrent()
            val timeAtClose = testScheduler.currentTime
            store.close()
            runCurrent()
            assertTrue(collector.isCompleted, "Close must cancel the pending status retry")
            assertEquals(timeAtClose, testScheduler.currentTime)
            assertIs<CancellationException>(collector.await().exceptionOrNull())
            assertTrue(kotlinx.coroutines.currentCoroutineContext().isActive)
        } finally {
            collector.cancel()
            store.close()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.engine(
        key: TestKey,
        sot: InMemorySourceOfTruth<TestKey, String>,
        bookkeeper: Bookkeeper,
    ): KeyEngine<TestKey, String> = KeyEngine(
        key = key,
        keyId = KeyId.from(key),
        fetcher = ResultFetcher { FetcherResult.Success("fetched", etag = "fetch-etag") },
        sot = sot,
        bookkeeper = bookkeeper,
        validator = DefaultFreshnessValidator,
        wallClock = FakeWallClock(now = 0L),
        engineScope = backgroundScope,
    )

    private class FailingStatusBookkeeper(
        private val delegate: Bookkeeper = InMemoryBookkeeper(),
    ) : Bookkeeper by delegate {
        var failure: Throwable? = null

        override suspend fun status(key: StoreKey): KeyStatus? {
            failure?.let { throw it }
            return delegate.status(key)
        }
    }
}
