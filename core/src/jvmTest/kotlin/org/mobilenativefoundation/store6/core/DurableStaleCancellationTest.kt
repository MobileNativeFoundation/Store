package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class, ExperimentalCoroutinesApi::class)
class DurableStaleCancellationTest {
    @Test
    fun invalidate_cancelledAfterDurableMarkRevokesEarlierEvidence() = runTest {
        cancellationAfterDurableMark { invalidate() }
    }

    @Test
    fun acknowledgementWithoutEvidence_cancelledAfterDurableMarkRevokesEarlierEvidence() = runTest {
        cancellationAfterDurableMark {
            applyAcknowledgement("cancelled", "cancelled-etag", freshnessEvidence = null, adoption = null)
        }
    }

    private suspend fun TestScope.cancellationAfterDurableMark(
        mutation: suspend KeyEngine<TestKey, String>.() -> Unit,
    ) {
        val key = TestKey("cancelled-stale-mark")
        val durable = InMemoryBookkeeper()
        durable.recordSuccess(key, EngineStoreMeta(0L, "previous-etag"))
        val markEntered = CompletableDeferred<Unit>()
        val releaseMark = CompletableDeferred<Unit>()
        val markReturned = CompletableDeferred<Unit>()
        lateinit var mutationCaller: Job
        var cancelNextMark = true
        val bookkeeper = object : Bookkeeper by durable {
            override suspend fun markStale(key: StoreKey) {
                if (!cancelNextMark) {
                    durable.markStale(key)
                    return
                }
                cancelNextMark = false
                markEntered.complete(Unit)
                releaseMark.await()
                durable.markStale(key)
                mutationCaller.cancel(CancellationException("cancelled after durable mark"))
                markReturned.complete(Unit)
            }
        }
        val sot = InMemorySourceOfTruth<TestKey, String>().also { it.write(key, "previous") }
        val engine = KeyEngine(
            key = key,
            keyId = KeyId.from(key),
            fetcher = ResultFetcher<TestKey, String> { error("unexpected fetch") },
            sot = sot,
            bookkeeper = bookkeeper,
            validator = DefaultFreshnessValidator,
            wallClock = FakeWallClock(now = 0L),
            engineScope = backgroundScope,
        )
        val evidence = engine.captureFreshness()
        val marked = async(start = CoroutineStart.UNDISPATCHED) {
            mutationCaller = checkNotNull(currentCoroutineContext()[Job])
            engine.mutation()
        }
        markEntered.await()

        // Model preemption while an independent state transition owns the mutex; no seam callback
        // acquires an engine lock or suspends inside an engine state transition.
        val stateLock =
            KeyEngine::class.java.getDeclaredField("stateLock").let { field ->
                field.isAccessible = true
                field.get(engine) as Mutex
            }
        val lockHeld = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val contender = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            stateLock.withLock {
                lockHeld.complete(Unit)
                releaseLock.await()
            }
        }
        try {
            lockHeld.await()
            releaseMark.complete(Unit)
            markReturned.await()
            runCurrent()
            assertTrue(assertNotNull(durable.status(key)).durablyStale)
            releaseLock.complete(Unit)
            contender.join()
            assertFailsWith<CancellationException> { marked.await() }
            assertEquals("previous", sot.reader(key).first())

            engine.applyAcknowledgement("acknowledged", "ack-etag", evidence, adoption = null)

            assertTrue(
                assertNotNull(durable.status(key)).durablyStale,
                "Acknowledgement evidence captured before a committed stale mark must stay revoked",
            )
            assertEquals("acknowledged", engine.get(Freshness.LocalOnly))
            engine.stream(Freshness.LocalOnly).test {
                assertTrue(assertIs<StoreResult.Data<String>>(awaitItem()).isStale)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseMark.complete(Unit)
            releaseLock.complete(Unit)
            contender.cancel()
            marked.cancel()
        }
    }
}
