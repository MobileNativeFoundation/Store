package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

abstract class StoreInvalidationStressConformance : SourceOfTruthSubstitutionTest() {
    @Test
    fun invalidate_burstOf10k_convergesWithoutLosingFinalStaleness() =
        runTest(timeout = 240.seconds) {
            val callsLock = Mutex()
            var calls = 0
            var gateNextFetch = false
            val finalFetchEntered = CompletableDeferred<Unit>()
            val finalFetchCall = CompletableDeferred<Int>()
            val releaseFinalFetch = CompletableDeferred<Unit>()
            val store = testStore<TestKey, String> {
                fetcher {
                    val (call, shouldGate) = callsLock.withLock {
                        calls += 1
                        val armed = gateNextFetch
                        gateNextFetch = false
                        calls to armed
                    }
                    if (shouldGate) {
                        finalFetchCall.complete(call)
                        finalFetchEntered.complete(Unit)
                        releaseFinalFetch.await()
                    }
                    "v$call"
                }
            }
            val initialDataSeen = CompletableDeferred<Unit>()
            val burstCollector = backgroundScope.launch {
                store.stream(TestKey("1")).collect { result ->
                    if (result is StoreResult.Data) initialDataSeen.complete(Unit)
                }
            }

            try {
                initialDataSeen.awaitFromDefaultContext()
                coroutineScope {
                    repeat(10) {
                        launch {
                            repeat(1_000) {
                                store.invalidate(TestKey("1"))
                                store.get(TestKey("1"))
                            }
                        }
                    }
                }
                assertTrue(callsLock.withLock { calls >= 2 })

                burstCollector.cancelAndJoin()
                store.get(TestKey("1"), Freshness.MustBeFresh)
                callsLock.withLock { gateNextFetch = true }
                store.invalidate(TestKey("1"))

                store.stream(TestKey("1")).test(timeout = 60.seconds) {
                    val finalStale = assertIs<StoreResult.Data<String>>(awaitItem())
                    assertTrue(finalStale.isStale)
                    assertTrue(finalStale.refreshing)
                    finalFetchEntered.awaitFromDefaultContext()
                    val expectedFreshValue = "v${finalFetchCall.awaitFromDefaultContext()}"
                    releaseFinalFetch.complete(Unit)
                    while (true) {
                        when (val item = awaitItem()) {
                            is StoreResult.Data -> {
                                if (!item.isStale && !item.refreshing) {
                                    kotlin.test.assertEquals(expectedFreshValue, item.value)
                                    break
                                }
                            }
                            is StoreResult.Loading -> Unit
                            is StoreResult.Error -> fail("unexpected stress error: ${item.error}")
                            is StoreResult.Revalidated -> fail("Success fetches must not revalidate")
                        }
                    }
                    cancelAndIgnoreRemainingEvents()
                }
            } finally {
                releaseFinalFetch.complete(Unit)
                burstCollector.cancel()
                store.close()
                burstCollector.join()
            }
        }
}

class StoreInvalidationStressTest : StoreInvalidationStressConformance()

private suspend fun <T> CompletableDeferred<T>.awaitFromDefaultContext(): T =
    withContext(Dispatchers.Default) {
        await()
    }
