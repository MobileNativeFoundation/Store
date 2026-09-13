package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.internal.DefaultFreshnessValidator
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import org.mobilenativefoundation.store6.core.internal.KeyEngine
import org.mobilenativefoundation.store6.core.internal.KeyId
import org.mobilenativefoundation.store6.core.internal.ResultFetcher
import org.mobilenativefoundation.store6.core.seam.Overlay
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class, ExperimentalCoroutinesApi::class)
class ProjectionConsumerIsolationTest {
    @Test
    fun blockedPublicConsumer_keepsBothProjectionWritersAndFinalWakeLive() = runTest {
        val firstKey = TestKey("first")
        val secondKey = TestKey("second")
        val source = InMemorySourceOfTruth<TestKey, String>()
        source.write(firstKey, "first")
        source.write(secondKey, "second")
        val projectionChanges = MutableSharedFlow<StoreKey>(replay = 1)
        val input = MutableStateFlow(0)
        val overlay = object : Overlay<TestKey, String> {
            override val changes = projectionChanges

            override fun apply(key: TestKey, base: String?): String? =
                base?.let { "$it:${input.value}" }
        }
        fun engine(key: TestKey) = KeyEngine(
            key = key,
            keyId = KeyId.from(key),
            fetcher = ResultFetcher<TestKey, String> { error("unexpected fetch") },
            sot = source,
            bookkeeper = InMemoryBookkeeper(),
            validator = DefaultFreshnessValidator,
            wallClock = FakeWallClock(now = 0L),
            engineScope = backgroundScope,
            overlay = overlay,
        )
        val first = engine(firstKey)
        val second = engine(secondKey)
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirstConsumer = CompletableDeferred<Unit>()
        val secondBurst = CompletableDeferred<Unit>()
        val firstFinal = CompletableDeferred<String>()
        val secondFinal = CompletableDeferred<String>()
        val firstCollector = backgroundScope.launch {
            first.stream(Freshness.LocalOnly).collect { result ->
                if (result is StoreResult.Data) {
                    if (result.value == "first:0") {
                        firstStarted.complete(Unit)
                        releaseFirstConsumer.await()
                    }
                    if (result.value == "first:256") firstFinal.complete(result.value)
                }
            }
        }
        val secondCollector = backgroundScope.launch {
            second.stream(Freshness.LocalOnly).collect { result ->
                if (result is StoreResult.Data) {
                    if (result.value == "second:0") secondStarted.complete(Unit)
                    if (result.value == "second:255") secondBurst.complete(Unit)
                    if (result.value == "second:256") secondFinal.complete(result.value)
                }
            }
        }
        try {
            firstStarted.await()
            secondStarted.await()
            projectionChanges.subscriptionCount.first { it == 2 }
            repeat(255) { revision ->
                input.value = revision + 1
                projectionChanges.emit(firstKey)
                projectionChanges.emit(secondKey)
            }
            secondBurst.await()
            runCurrent()

            // After the burst drains, each key receives only one wake for its final input.
            input.value = 256
            projectionChanges.emit(firstKey)
            projectionChanges.emit(secondKey)
            assertEquals("second:256", secondFinal.await())
            releaseFirstConsumer.complete(Unit)
            assertEquals("first:256", firstFinal.await())
        } finally {
            releaseFirstConsumer.complete(Unit)
            firstCollector.cancel()
            secondCollector.cancel()
        }
    }
}
