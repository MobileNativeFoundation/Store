package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.internal.DefaultFreshnessValidator
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import org.mobilenativefoundation.store6.core.internal.KeyEngine
import org.mobilenativefoundation.store6.core.internal.KeyId
import org.mobilenativefoundation.store6.core.internal.ResultFetcher
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.Overlay
import org.mobilenativefoundation.store6.core.seam.SourceAdoption
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class, ExperimentalCoroutinesApi::class)
class SourceAdoptionObservationTest {
    @Test
    fun observationProvesCommitBeforeApplyReturns_andKeepsOnlyQueuedSuffix() = runTest {
        val key = TestKey("adoption")
        val identity = SourceAdoption()
        val observations = mutableListOf<Pair<Int?, SourceAdoption?>>()
        val delegate = InMemorySourceOfTruth<TestKey, Int>().also { it.write(key, 0) }
        val rowPublished = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val sot = object : SourceOfTruth<TestKey, Int> by delegate {
            override suspend fun write(key: TestKey, value: Int) {
                delegate.write(key, value)
                rowPublished.complete(Unit)
                releaseWrite.await()
            }
        }
        val successEntered = CompletableDeferred<Unit>()
        val releaseSuccess = CompletableDeferred<Unit>()
        val durable = InMemoryBookkeeper()
        var gateSuccess = true
        val bookkeeper = object : Bookkeeper by durable {
            override suspend fun recordSuccess(key: StoreKey, meta: StoreMeta) {
                if (gateSuccess) {
                    successEntered.complete(Unit)
                    releaseSuccess.await()
                }
                durable.recordSuccess(key, meta)
            }
        }
        val engine = KeyEngine(
            key = key,
            keyId = KeyId.from(key),
            fetcher = ResultFetcher { FetcherResult.Success(0) },
            sot = sot,
            bookkeeper = bookkeeper,
            validator = DefaultFreshnessValidator,
            wallClock = FakeWallClock(now = 0L),
            engineScope = backgroundScope,
            overlay = object : Overlay<TestKey, Int> {
                override val changes = emptyFlow<StoreKey>()
                override fun apply(key: TestKey, base: Int?): Int? =
                    apply(key, base, adoption = null)

                override fun apply(key: TestKey, base: Int?, adoption: SourceAdoption?): Int? {
                    observations += base to adoption
                    return base?.plus(if (adoption === identity) 1 else 2)
                }
            },
        )
        engine.stream(Freshness.LocalOnly).test {
            assertEquals(2, assertIs<StoreResult.Data<Int>>(awaitItem()).value)
            runCurrent()
            val evidence = engine.captureFreshness()
            val applying = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.applyAcknowledgement(1, "ack", evidence, identity)
            }
            rowPublished.await()
            runCurrent()
            assertFalse(observations.any { it.first == 1 })
            releaseWrite.complete(Unit)
            runCurrent()
            assertTrue(observations.any { it.first == 1 && it.second === identity })
            assertFalse(observations.any { it.first == 1 && it.second == null })
            assertTrue(successEntered.isCompleted)
            assertFalse(applying.isCompleted, "Adoption proof must precede caller return")
            gateSuccess = false
            releaseSuccess.complete(Unit)
            applying.await()

            engine.confirmFresh("metadata-successor")
            runCurrent()
            assertSame(identity, observations.last().second)

            delegate.write(key, 1)
            runCurrent()
            assertEquals(1, observations.last().first)
            assertNull(observations.last().second)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
