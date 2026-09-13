package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.internal.DefaultFreshnessValidator
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import org.mobilenativefoundation.store6.core.internal.KeyEngine
import org.mobilenativefoundation.store6.core.internal.KeyId
import org.mobilenativefoundation.store6.core.internal.ResultFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class, ExperimentalCoroutinesApi::class)
class CapturedWriterOriginRaceTest {
    @Test
    fun secondWriterAfterInitialSnapshot_preservesCapturedWriterSotOrigin() = runTest {
        val key = TestKey("captured-writer-origin")
        val originClassificationEntered = CompletableDeferred<Unit>()
        val releaseOriginClassification = CompletableDeferred<Unit>()
        val releaseSecondWriterObservation = CompletableDeferred<Unit>()
        val engine = KeyEngine(
            key = key,
            keyId = KeyId.from(key),
            fetcher = ResultFetcher<TestKey, String> { error("unexpected fetch") },
            sot = InMemorySourceOfTruth<TestKey, String>(),
            bookkeeper = InMemoryBookkeeper(),
            validator = DefaultFreshnessValidator,
            wallClock = FakeWallClock(now = 0L),
            engineScope = backgroundScope,
            beforeInitialOriginClassificationTestGate = {
                originClassificationEntered.complete(Unit)
                releaseOriginClassification.await()
            },
            beforeRawReaderObservationTestGate = { row ->
                // Keep the newer raw observation from conflating away the captured initial frame.
                if (row == "second") releaseSecondWriterObservation.await()
            },
        )
        engine.applyWrite("first")
        try {
            engine.stream(Freshness.LocalOnly).test {
                originClassificationEntered.await()
                engine.applyWrite("second")
                releaseOriginClassification.complete(Unit)

                val first = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("first", first.value)
                assertEquals(Origin.SOT, first.origin)

                releaseSecondWriterObservation.complete(Unit)
                val second = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("second", second.value)
                assertEquals(Origin.SOT, second.origin)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseOriginClassification.complete(Unit)
            releaseSecondWriterObservation.complete(Unit)
        }
    }
}
