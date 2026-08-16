package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.internal.DefaultFreshnessValidator
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.KeyEngine
import org.mobilenativefoundation.store6.core.internal.KeyId
import org.mobilenativefoundation.store6.core.internal.ResultFetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)
class SourceOfTruthFailureConformanceTest {

    @Test
    fun readerFailure_activeStreamEmitsTypedPersistenceAndRecovers() = runTest {
        val boom = IllegalStateException("reader outage")
        val sot = EpisodeSourceOfTruth(initial = "seed", failure = boom)
        val engine = localOnlyEngine(sot, backgroundScope)

        engine.stream(Freshness.LocalOnly).test {
            assertEquals("seed", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            sot.awaitLiveReader()

            sot.failActiveReader(retryFailures = 0)

            val error = assertIs<StoreResult.Error>(awaitItem())
            val persistence = assertIs<StoreError.Persistence>(error.error)
            assertMatchingFailure(persistence.cause, boom)

            sot.recoverWith("recovered")
            advanceTimeBy(READER_RETRY_MILLIS)
            runCurrent()

            assertEquals("recovered", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun readerFailure_initialLocalOnlyDoesNotReportMissing() = runTest {
        val boom = IllegalStateException("initial reader outage")
        val sot = InitialFailureThenRecoverySourceOfTruth(boom)
        val engine = localOnlyEngine(sot, backgroundScope)

        engine.stream(Freshness.LocalOnly).test {
            val error = assertIs<StoreResult.Error>(awaitItem())
            val persistence = assertIs<StoreError.Persistence>(error.error)
            assertMatchingFailure(persistence.cause, boom)

            advanceTimeBy(READER_RETRY_MILLIS)
            runCurrent()
            sot.pipelineStarted.await()
            expectNoEvents()

            sot.recoverWith("durable")
            runCurrent()

            assertEquals("durable", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun readerCompletion_isTypedAndRetriedDefensively() = runTest {
        val sot = CompletingSourceOfTruth(initial = "seed")
        val engine = localOnlyEngine(sot, backgroundScope)

        engine.stream(Freshness.LocalOnly).test {
            assertEquals("seed", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            sot.awaitLiveReader()

            sot.completeNormallyWith("recovered")

            val error = assertIs<StoreResult.Error>(awaitItem())
            val persistence = assertIs<StoreError.Persistence>(error.error)
            val cause = assertIs<IllegalStateException>(persistence.cause)
            assertTrue(cause.message.orEmpty().contains("completed normally"))

            advanceTimeBy(READER_RETRY_MILLIS)
            runCurrent()

            assertEquals("recovered", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            assertTrue(sot.readerCalls >= 3)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun readerCancellation_cancelsWithoutPersistenceEmission() = runTest {
        val sot = CancellingSourceOfTruth(initial = "seed")
        val engine = localOnlyEngine(sot, backgroundScope)

        engine.stream(Freshness.LocalOnly).test {
            assertEquals("seed", assertIs<StoreResult.Data<String>>(awaitItem()).value)
            sot.awaitLiveReader()
            val callsBeforeCancellation = sot.readerCalls

            sot.cancelActiveReader()
            sot.cancellationThrown.await()
            runCurrent()

            expectNoEvents()
            assertEquals(callsBeforeCancellation, sot.readerCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun persistentReaderFailure_coalescesEpisode_andStalledCollectorDoesNotBlockRecovery() =
        runTest {
            val boom = IllegalStateException("persistent reader outage")
            val sot = EpisodeSourceOfTruth(initial = "seed", failure = boom)
            val key = TestKey("key")
            val engine =
                KeyEngine(
                    key = key,
                    keyId = KeyId.from(key),
                    fetcher = ResultFetcher { FetcherResult.Success("unused") },
                    sot = sot,
                    bookkeeper = InMemoryBookkeeper(),
                    validator = DefaultFreshnessValidator,
                    wallClock = FakeWallClock(now = 0L),
                    engineScope = backgroundScope,
                )

            turbineScope {
                val stalledSawSeed = CompletableDeferred<Unit>()
                val stalledOnFirstError = CompletableDeferred<Unit>()
                val releaseStalledCollector = CompletableDeferred<Unit>()
                val stalled =
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        engine.stream(Freshness.LocalOnly).buffer(0).collect { result ->
                            when (result) {
                                is StoreResult.Data -> {
                                    if (result.value == "seed") stalledSawSeed.complete(Unit)
                                }
                                is StoreResult.Error -> {
                                    assertPersistenceFailure(result, boom)
                                    if (stalledOnFirstError.complete(Unit)) {
                                        releaseStalledCollector.await()
                                    }
                                }
                                is StoreResult.Loading,
                                is StoreResult.Revalidated,
                                -> Unit
                            }
                        }
                    }
                val fast = engine.stream(Freshness.LocalOnly).testIn(backgroundScope)
                stalledSawSeed.await()
                assertEquals("seed", assertIs<StoreResult.Data<String>>(fast.awaitItem()).value)
                sot.awaitLiveReader()

                val callsAtEpisodeStart = sot.readerCalls
                sot.failActiveReader(retryFailures = 3)

                stalledOnFirstError.await()
                assertPersistenceFailure(fast.awaitItem(), boom)

                repeat(3) {
                    advanceTimeBy(READER_RETRY_MILLIS)
                    runCurrent()
                    fast.expectNoEvents()
                }
                assertEquals(callsAtEpisodeStart + 3, sot.readerCalls)

                // The first Error is still blocking the direct zero-buffer collector. Its queued
                // recovery must not backpressure the shared retrying reader or the fast peer.
                sot.recoverWith("recovered")
                advanceTimeBy(READER_RETRY_MILLIS)
                runCurrent()

                assertEquals("recovered", assertIs<StoreResult.Data<String>>(fast.awaitItem()).value)
                sot.awaitLiveReader()

                sot.failActiveReader(retryFailures = 0)
                assertPersistenceFailure(fast.awaitItem(), boom)

                releaseStalledCollector.complete(Unit)
                stalled.cancel()
                fast.cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun externalNullMetaRowServedBeforeFailure_marksErrorServedStale() = runTest {
        val boom = IllegalStateException("revalidation failed")
        val revalidationStarted = CompletableDeferred<Unit>()
        val releaseRevalidation = CompletableDeferred<Unit>()
        var calls = 0
        val sot = MutableSourceOfTruth()
        val store =
            store<TestKey, String> {
                persistence(sot)
                fetcher {
                    calls++
                    if (calls == 1) {
                        "seed"
                    } else {
                        revalidationStarted.complete(Unit)
                        releaseRevalidation.await()
                        throw boom
                    }
                }
            }

        try {
            store.stream(TestKey("key")).test {
                assertIs<StoreResult.Loading>(awaitItem())
                assertEquals("seed", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                runCurrent()

                sot.publishExternal("external")
                val external = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("external", external.value)
                assertEquals(Origin.SOT, external.origin)
                assertTrue(external.isStale)
                assertTrue(external.refreshing)

                revalidationStarted.await()
                releaseRevalidation.complete(Unit)

                val error = assertIs<StoreResult.Error>(awaitItem())
                assertTrue(error.servedStale)
                val fetch = assertIs<StoreError.Fetch>(error.error)
                assertMatchingFailure(fetch.cause, boom)
                assertEquals(2, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun activeNullMetaRow_triggersOneRevalidationPerResidenceRevision() = runTest {
        val firstFailure = IllegalStateException("first revalidation failed")
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val thirdStarted = CompletableDeferred<Unit>()
        val releaseThird = CompletableDeferred<Unit>()
        var calls = 0
        val sot = MutableSourceOfTruth()
        val store =
            store<TestKey, String> {
                persistence(sot)
                fetcher {
                    calls++
                    when (calls) {
                        1 -> "seed"
                        2 -> {
                            secondStarted.complete(Unit)
                            releaseSecond.await()
                            throw firstFailure
                        }
                        3 -> {
                            thirdStarted.complete(Unit)
                            releaseThird.await()
                            "refreshed"
                        }
                        else -> error("unexpected revalidation $calls")
                    }
                }
            }

        try {
            store.stream(TestKey("key")).test {
                assertIs<StoreResult.Loading>(awaitItem())
                assertEquals("seed", assertIs<StoreResult.Data<String>>(awaitItem()).value)

                sot.publishExternal("external-1")
                val firstExternal = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("external-1", firstExternal.value)
                assertTrue(firstExternal.isStale)
                secondStarted.await()
                assertEquals(2, calls)

                releaseSecond.complete(Unit)
                val firstError = assertIs<StoreResult.Error>(awaitItem())
                assertTrue(firstError.servedStale)
                assertMatchingFailure(
                    assertIs<StoreError.Fetch>(firstError.error).cause,
                    firstFailure,
                )
                runCurrent()
                expectNoEvents()
                assertEquals(2, calls)

                sot.publishExternal("external-2")
                val secondExternal = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("external-2", secondExternal.value)
                assertTrue(secondExternal.isStale)
                thirdStarted.await()
                assertEquals(3, calls)

                releaseThird.complete(Unit)
                assertEquals("refreshed", assertIs<StoreResult.Data<String>>(awaitItem()).value)
                runCurrent()
                assertEquals(3, calls)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            store.close()
        }
    }

    private fun localOnlyEngine(
        sot: SourceOfTruth<TestKey, String>,
        scope: CoroutineScope,
    ): KeyEngine<TestKey, String> {
        val key = TestKey("key")
        return KeyEngine(
            key = key,
            keyId = KeyId.from(key),
            fetcher = ResultFetcher { FetcherResult.Success("unused") },
            sot = sot,
            bookkeeper = InMemoryBookkeeper(),
            validator = DefaultFreshnessValidator,
            wallClock = FakeWallClock(now = 0L),
            engineScope = scope,
        )
    }

    private fun assertPersistenceFailure(
        result: StoreResult<String>,
        cause: Throwable,
    ) {
        val error = assertIs<StoreResult.Error>(result)
        val persistence = assertIs<StoreError.Persistence>(error.error)
        assertMatchingFailure(persistence.cause, cause)
    }

    private fun assertMatchingFailure(
        actual: Throwable?,
        expected: Throwable,
    ) {
        val failure = assertIs<IllegalStateException>(actual)
        assertEquals(expected.message, failure.message)
    }

    private class EpisodeSourceOfTruth(
        initial: String?,
        private val failure: Throwable,
    ) : SingleRowTestSourceOfTruth<String> {
        private var row: String? = initial
        private var retryFailuresRemaining = 0
        private val activeFailures = Channel<Throwable>(Channel.UNLIMITED)
        private val liveReaderStarts = Channel<Unit>(Channel.UNLIMITED)

        var readerCalls: Int = 0
            private set

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls++
            val failBeforeRow = retryFailuresRemaining > 0
            if (failBeforeRow) retryFailuresRemaining--
            return flow {
                if (failBeforeRow) throw failure
                emit(row)
                liveReaderStarts.send(Unit)
                throw activeFailures.receive()
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            row = value
        }

        override suspend fun delete(key: TestKey) {
            row = null
        }

        suspend fun awaitLiveReader() {
            liveReaderStarts.receive()
        }

        fun failActiveReader(retryFailures: Int) {
            retryFailuresRemaining = retryFailures
            check(activeFailures.trySend(failure).isSuccess)
        }

        fun recoverWith(value: String) {
            row = value
            retryFailuresRemaining = 0
        }
    }

    private class InitialFailureThenRecoverySourceOfTruth(
        private val failure: Throwable,
    ) : SingleRowTestSourceOfTruth<String> {
        private var readerCalls = 0
        private var recoveredRow: String? = null
        private val releaseRecovery = CompletableDeferred<Unit>()
        val pipelineStarted = CompletableDeferred<Unit>()

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls++
            return if (readerCalls == 1) {
                flow { throw failure }
            } else {
                flow {
                    pipelineStarted.complete(Unit)
                    releaseRecovery.await()
                    emit(recoveredRow)
                    awaitCancellation()
                }
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            recoveredRow = value
        }

        override suspend fun delete(key: TestKey) {
            recoveredRow = null
        }

        fun recoverWith(value: String) {
            recoveredRow = value
            releaseRecovery.complete(Unit)
        }
    }

    private class CompletingSourceOfTruth(
        initial: String?,
    ) : SingleRowTestSourceOfTruth<String> {
        private var row: String? = initial
        private var completionConsumed = false
        private val releaseCompletion = CompletableDeferred<Unit>()
        private val liveReaderStarts = Channel<Unit>(Channel.UNLIMITED)

        var readerCalls: Int = 0
            private set

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls++
            return flow {
                emit(row)
                liveReaderStarts.send(Unit)
                if (!completionConsumed) {
                    releaseCompletion.await()
                    completionConsumed = true
                    return@flow
                }
                awaitCancellation()
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            row = value
        }

        override suspend fun delete(key: TestKey) {
            row = null
        }

        suspend fun awaitLiveReader() {
            liveReaderStarts.receive()
        }

        fun completeNormallyWith(value: String) {
            row = value
            releaseCompletion.complete(Unit)
        }
    }

    private class CancellingSourceOfTruth(
        initial: String?,
    ) : SingleRowTestSourceOfTruth<String> {
        private var row: String? = initial
        private val releaseCancellation = CompletableDeferred<Unit>()
        private val liveReaderStarts = Channel<Unit>(Channel.UNLIMITED)
        val cancellationThrown = CompletableDeferred<Unit>()

        var readerCalls: Int = 0
            private set

        override fun reader(key: TestKey): Flow<String?> {
            readerCalls++
            return flow {
                emit(row)
                liveReaderStarts.send(Unit)
                releaseCancellation.await()
                cancellationThrown.complete(Unit)
                throw CancellationException("reader cancelled")
            }
        }

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            row = value
        }

        override suspend fun delete(key: TestKey) {
            row = null
        }

        suspend fun awaitLiveReader() {
            liveReaderStarts.receive()
        }

        fun cancelActiveReader() {
            releaseCancellation.complete(Unit)
        }
    }

    private class MutableSourceOfTruth : SingleRowTestSourceOfTruth<String> {
        private val rows =
            MutableSharedFlow<String?>(
                replay = 1,
                extraBufferCapacity = 16,
            ).also { flow -> check(flow.tryEmit(null)) }

        override fun reader(key: TestKey): Flow<String?> = rows

        override suspend fun write(
            key: TestKey,
            value: String,
        ) {
            rows.emit(value)
        }

        override suspend fun delete(key: TestKey) {
            rows.emit(null)
        }

        suspend fun publishExternal(value: String) {
            rows.emit(value)
        }
    }

    private companion object {
        const val READER_RETRY_MILLIS = 100L
    }
}
