package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import org.mobilenativefoundation.store6.core.seam.KeyEvents
import org.mobilenativefoundation.store6.core.seam.runtime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
class StoreRuntimeTest {
    private suspend fun app.cash.turbine.ReceiveTurbine<StoreResult<String>>.awaitDataValue(
        expected: String,
    ): StoreResult.Data<String> {
        while (true) {
            val item = awaitItem()
            if (item is StoreResult.Data<String> && item.value == expected) return item
        }
    }

    @Test
    fun keyEvents_writtenInvalidatedDeleted_inOrder() = runTest {
        val store = store<TestKey, String> { fetcher { "v" } }
        val runtime = assertNotNull(store.runtime())
        runtime.keyEvents.test {
            store.get(TestKey("1"))
            // Written is tryEmitted BEFORE the ticket completes (placement pin), so it is on the
            // bus before this thread resumed from get() -- strictly before the Invalidated below.
            val written = assertIs<KeyEvents.Written>(awaitItem())
            assertEquals("1", written.key.canonicalId())
            assertEquals(Origin.FETCHER, written.origin)
            store.invalidate(TestKey("1"))
            assertIs<KeyEvents.Invalidated>(awaitItem())
            store.clear(TestKey("1"))
            assertIs<KeyEvents.Deleted>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun writeHandle_apply_emitsSotData_withoutFetch() = runTest {
        var fetches = 0
        val store = store<TestKey, String> { fetcher { fetches++; "fetched" } }
        val handle = assertNotNull(store.runtime()).writeHandle
        store.stream(TestKey("1")).test {
            awaitDataValue("fetched")
            handle.apply(TestKey("1"), "applied")
            val applied = awaitDataValue("applied")
            assertEquals(Origin.SOT, applied.origin)
            assertEquals(1, fetches)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun writeHandle_apply_settledEnvelopeKeepsSotOrigin() = runTest {
        // Guards the T0-verified recognition amendment: the envelope-freshness recognition sites
        // compare envelope.origin == Origin.FETCHER; unless generalized to installed-envelope
        // identity, the SoT echo row would not reuse the installed SOT envelope and a fresh
        // collection's first Data would expose a FETCHER (or conservative) envelope.
        val store = store<TestKey, String> { fetcher { "fetched" } }
        val handle = store.runtime()!!.writeHandle
        store.get(TestKey("1"))
        handle.apply(TestKey("1"), "applied")
        store.stream(TestKey("1")).test {
            // Fresh collection after apply and its source-of-truth echo settle.
            val first = awaitDataValue("applied")
            assertEquals(Origin.SOT, first.origin)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun writeHandle_apply_echoRowReusesInstalledEnvelope_andExternalWriteStillWins() = runTest {
        // The echo is the one matching pre-cutoff row and reuses the installed SOT envelope; a
        // later external source-of-truth write is post-cutoff and wins as later authority.
        val sot = InMemorySourceOfTruth<TestKey, String>()
        val secondFetchStarted = CompletableDeferred<Unit>()
        val releaseSecondFetch = CompletableDeferred<Unit>()
        var fetches = 0
        val store = store<TestKey, String> {
            fetcher {
                when (val call = ++fetches) {
                    1 -> "fetched"
                    2 -> {
                        secondFetchStarted.complete(Unit)
                        releaseSecondFetch.await()
                        "replacement"
                    }

                    else -> error("unexpected fetch call $call")
                }
            }
            persistence(sot)
        }
        val handle = assertNotNull(store.runtime()).writeHandle
        try {
            store.stream(TestKey("1")).test {
                awaitDataValue("fetched")
                handle.apply(TestKey("1"), "applied")
                assertEquals(Origin.SOT, awaitDataValue("applied").origin)
                sot.write(TestKey("1"), "external")
                withContext(Dispatchers.Default) {
                    secondFetchStarted.await()
                }
                assertEquals(Origin.SOT, awaitDataValue("external").origin)
                releaseSecondFetch.complete(Unit)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            releaseSecondFetch.complete(Unit)
            store.close()
        }
    }

    @Test
    fun writeHandle_markStale_signalsRefetch() = runTest {
        var fetches = 0
        val store = store<TestKey, String> { fetcher { fetches++; "v$fetches" } }
        store.stream(TestKey("1")).test {
            awaitDataValue("v1")
            store.runtime()!!.writeHandle.markStale(TestKey("1"))
            awaitDataValue("v2")
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun writeHandle_confirmFresh_clearsStalenessWithoutFetch() = runTest {
        var fetches = 0
        val store = store<TestKey, String> { fetcher { fetches++; "v$fetches" } }
        assertEquals("v1", store.get(TestKey("1")))
        store.invalidate(TestKey("1"))
        store.runtime()!!.writeHandle.confirmFresh(TestKey("1"), etag = null)
        assertEquals("v1", store.get(TestKey("1")))
        assertEquals(1, fetches)
        store.close()
    }
}

// Turbine's 3s default would nest inside the 25s shadow. Raising the Turbine deadline above
// the shadow makes runTest the only effective timeout.
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
