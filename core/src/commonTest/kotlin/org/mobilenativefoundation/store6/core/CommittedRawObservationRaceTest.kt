package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class, ExperimentalCoroutinesApi::class)
class CommittedRawObservationRaceTest {
    @Test
    fun initialAbsenceReplan_afterWriteReturnWaitsForRawWriterObservation() = runTest {
        val key = TestKey("committed-raw-observation")
        val releaseFetch = CompletableDeferred<Unit>()
        val absenceReplanEntered = CompletableDeferred<Unit>()
        val releaseAbsenceReplan = CompletableDeferred<Unit>()
        val successEntered = CompletableDeferred<Unit>()
        val releaseSuccess = CompletableDeferred<Unit>()
        val writerNotificationQueued = CompletableDeferred<Unit>()
        val releaseWriterObservation = CompletableDeferred<Unit>()
        val durable = InMemoryBookkeeper()
        var statusReadsBeforeGate = 0
        val bookkeeper = object : Bookkeeper by durable {
            override suspend fun status(key: StoreKey): KeyStatus? {
                if (statusReadsBeforeGate > 0 && --statusReadsBeforeGate == 0) {
                    absenceReplanEntered.complete(Unit)
                    releaseAbsenceReplan.await()
                }
                return durable.status(key)
            }

            override suspend fun recordSuccess(key: StoreKey, meta: StoreMeta) {
                successEntered.complete(Unit)
                releaseSuccess.await()
                durable.recordSuccess(key, meta)
            }
        }
        var deliveries = 0
        var fetches = 0
        val engine = KeyEngine(
            key = key,
            keyId = KeyId.from(key),
            fetcher = ResultFetcher<TestKey, String> {
                fetches += 1
                releaseFetch.await()
                FetcherResult.Success("fetched", etag = "fetched-etag")
            },
            sot = InMemorySourceOfTruth<TestKey, String>(),
            bookkeeper = bookkeeper,
            validator = DefaultFreshnessValidator,
            wallClock = FakeWallClock(now = 0L),
            engineScope = backgroundScope,
            beforeReaderDeliveryTestGate = {
                if (++deliveries == 1) {
                    // Resolve absence before pausing its replan inside the collector mutex.
                    statusReadsBeforeGate = 2
                }
            },
            beforeRawReaderObservationTestGate = { row ->
                if (row == "fetched") {
                    // The source has published its row; only downstream engine capture is paused.
                    writerNotificationQueued.complete(Unit)
                    releaseWriterObservation.await()
                }
            },
        )
        try {
            engine.stream(Freshness.CachedOrFetch).test {
                assertIs<StoreResult.Loading>(awaitItem())
                absenceReplanEntered.await()
                releaseFetch.complete(Unit)
                successEntered.await()
                writerNotificationQueued.await()

                releaseAbsenceReplan.complete(Unit)
                runCurrent()
                expectNoEvents()

                releaseWriterObservation.complete(Unit)
                val data = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("fetched", data.value)
                assertEquals(Origin.FETCHER, data.origin)
                assertEquals(1, fetches)
                releaseSuccess.complete(Unit)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseFetch.complete(Unit)
            releaseAbsenceReplan.complete(Unit)
            releaseWriterObservation.complete(Unit)
            releaseSuccess.complete(Unit)
        }
    }
}
