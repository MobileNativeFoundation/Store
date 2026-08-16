package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StoreBackpressureConformanceTest {
    @Test
    fun slowCollector_doesNotBlockFastCollector_orEngine_andIsBoundedPerCycle() =
        runTest(timeout = 60.seconds) {
            val key = TestKey("backpressure")
            val slowGate = CompletableDeferred<Unit>()
            val slowParked = CompletableDeferred<Unit>()
            val slowLatestSerial = MutableStateFlow(0)
            val fastLatestSerial = MutableStateFlow(0)
            var slowDeliveries = 0
            val fetchSerial = MutableStateFlow(0)
            val store =
                store<TestKey, String> {
                    fetcher {
                        val next = fetchSerial.value + 1
                        fetchSerial.value = next
                        "v$next"
                    }
                }
            val slowCollector =
                backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    store.stream(key).collect { result ->
                        result.throwIfError()
                        slowDeliveries += 1
                        slowParked.complete(Unit)
                        slowGate.await()
                        if (result is StoreResult.Data && !result.isStale && !result.refreshing) {
                            slowLatestSerial.value = result.value.removePrefix("v").toInt()
                        }
                    }
                }

            var fastCollector = backgroundScope.launch { }
            try {
                slowParked.await()
                assertTrue(!slowGate.isCompleted, "slow collector must be parked before cycles")
                fastCollector =
                    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        store.stream(key).collect { result ->
                            result.throwIfError()
                            if (result is StoreResult.Data && !result.isStale && !result.refreshing) {
                                fastLatestSerial.value = result.value.removePrefix("v").toInt()
                            }
                        }
                    }
                awaitUntil { fastLatestSerial.value >= 1 }

                repeat(CYCLES) {
                    val before = fastLatestSerial.value
                    store.invalidate(key)
                    awaitUntil { fastLatestSerial.value > before }
                }
                awaitUntil { fastLatestSerial.value == fetchSerial.value }
                val finalSerial = fetchSerial.value
                val finalValue = "v$finalSerial"
                assertEquals(finalSerial, fastLatestSerial.value)
                assertEquals(finalValue, store.get(key, Freshness.LocalOnly))

                slowGate.complete(Unit)
                awaitUntil { slowLatestSerial.value == finalSerial }
                slowCollector.cancelAndJoin()
                assertTrue(
                    slowDeliveries <= MAX_BOUNDED_DELIVERIES,
                    // The operator unit test pins its internal pending queue to <= 4. This public
                    // engine-level bound includes the lifecycle deliveries around each cycle.
                    "slow collector received $slowDeliveries items for $CYCLES cycles",
                )
                assertEquals(finalSerial, slowLatestSerial.value)
            } finally {
                slowGate.complete(Unit)
                slowCollector.cancelAndJoin()
                fastCollector.cancelAndJoin()
                store.close()
            }
        }

    @Test
    fun everyCollector_eventuallyObservesLatestRow() = runTest(timeout = 60.seconds) {
        val key = TestKey("eventual")
        val delayedGate = CompletableDeferred<Unit>()
        val delayedParked = CompletableDeferred<Unit>()
        val delayedLatest = MutableStateFlow<String?>(null)
        val fastLatest = MutableStateFlow<String?>(null)
        var serial = 0
        val fetchFinal = MutableStateFlow(false)
        val store = store<TestKey, String> {
            fetcher { if (fetchFinal.value) "final" else "v${++serial}" }
        }
        val finalValue = "final"
        val delayedCollector =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                store.stream(key)
                    .onEach { result ->
                        result.throwIfError()
                        delayedParked.complete(Unit)
                        delayedGate.await()
                    }
                    .filterIsInstance<StoreResult.Data<String>>()
                    .map { it.value }
                    .onEach { delayedLatest.value = it }
                    .first { it == finalValue }
            }

        val fastCollector =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                store.stream(key)
                    .onEach { result -> result.throwIfError() }
                    .filterIsInstance<StoreResult.Data<String>>()
                    .map { it.value }
                    .onEach { fastLatest.value = it }
                    .first { it == finalValue }
            }

        try {
            delayedParked.await()
            assertTrue(!delayedGate.isCompleted, "delayed collector must be gated before cycles")
            awaitUntil { fastLatest.value != null }
            repeat(EVENTUAL_CYCLES) {
                val before = fastLatest.value
                store.invalidate(key)
                awaitUntil { fastLatest.value != before }
            }
            fetchFinal.value = true
            store.invalidate(key)

            awaitUntil(timeout = 5.seconds) { fastLatest.value == finalValue }
            assertEquals(finalValue, fastCollector.await())
            assertEquals(finalValue, store.get(key, Freshness.LocalOnly))
            delayedGate.complete(Unit)
            awaitUntil(timeout = 5.seconds) { delayedLatest.value == finalValue }
            assertEquals(finalValue, delayedCollector.await())
        } finally {
            delayedGate.complete(Unit)
            delayedCollector.cancelAndJoin()
            fastCollector.cancelAndJoin()
            store.close()
        }
    }

    private fun StoreResult<*>.throwIfError() {
        if (this is StoreResult.Error) {
            throw AssertionError("unexpected Store error: $error")
        }
    }

    private companion object {
        const val CYCLES = 25
        const val EVENTUAL_CYCLES = 10
        const val MAX_BOUNDED_DELIVERIES = 12
    }
}
