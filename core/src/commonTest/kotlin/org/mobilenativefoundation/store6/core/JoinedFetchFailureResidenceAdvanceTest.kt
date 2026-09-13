package org.mobilenativefoundation.store6.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.seam.runtime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalStoreApi::class)
class JoinedFetchFailureResidenceAdvanceTest {
    @Test
    fun joinedFetchFails_afterConcurrentWrite_getReturnsWrittenValue() = runTest {
        var calls = 0
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> {
                        fetchStarted.complete(Unit)
                        releaseFetch.await()
                        error("doomed fetch")
                    }

                    else -> error("unexpected fetch call $calls")
                }
            }
        }
        val key = TestKey("1")
        val handle = assertNotNull(store.runtime()).writeHandle

        try {
            val owner =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.get(key)
                }
            awaitFromDefault { fetchStarted.await() }
            val joiner = backgroundScope.async { store.get(key) }
            // Enroll the second demand into the still-gated ticket before the write lands.
            testScheduler.runCurrent()

            handle.apply(key, "written")
            releaseFetch.complete(Unit)

            assertEquals("written", awaitFromDefault { owner.await() })
            assertEquals("written", awaitFromDefault { joiner.await() })
            assertEquals(1, calls, "the failed fetch must not trigger a replacement fetch")
        } finally {
            releaseFetch.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    @Test
    fun staleIfErrorFetchFails_afterConcurrentWrite_returnsNewerCommittedValue() = runTest {
        var calls = 0
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val store = store<TestKey, String> {
            fetcher {
                when (++calls) {
                    1 -> "v1"
                    2 -> {
                        refreshStarted.complete(Unit)
                        releaseRefresh.await()
                        error("doomed refresh")
                    }

                    else -> error("unexpected fetch call $calls")
                }
            }
        }
        val key = TestKey("1")
        val handle = assertNotNull(store.runtime()).writeHandle

        try {
            assertEquals("v1", store.get(key))
            store.invalidate(key)

            val waiter =
                backgroundScope.async {
                    store.get(key, Freshness.StaleIfError)
                }
            awaitFromDefault { refreshStarted.await() }
            val joiner =
                backgroundScope.async {
                    store.get(key, Freshness.StaleIfError)
                }
            // Enroll the second demand into the still-gated refresh before the write lands.
            testScheduler.runCurrent()

            handle.apply(key, "written")
            releaseRefresh.complete(Unit)

            assertEquals("written", awaitFromDefault { waiter.await() })
            assertEquals("written", awaitFromDefault { joiner.await() })
            assertEquals(2, calls)
        } finally {
            releaseRefresh.complete(Unit)
            store.closeAndSettleForTest()
        }
    }

    // Preserve the real-time Default-dispatch hop (never virtual time) and let the suite-level
    // runTest bound own cancellation.
    private suspend fun <T> awaitFromDefault(block: suspend () -> T): T =
        withContext(Dispatchers.Default) {
            block()
        }
}

// Preserve the real-time Default-dispatch hop (never virtual time) and let the suite-level
// runTest bound own cancellation.
private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
