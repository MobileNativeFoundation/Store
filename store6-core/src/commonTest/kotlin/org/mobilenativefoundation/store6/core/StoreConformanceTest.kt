package org.mobilenativefoundation.store6.core

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

open class StoreConformanceTest : SourceOfTruthSubstitutionTest() {

    // (a) THE 001 acceptance test — cold stream: Loading then Data(origin=FETCHER). TEST-1 emission-sequence seed.
    @Test
    fun coldStream_noCachedValue_emitsLoadingThenDataFromFetcher() = runTest {
        val store = testStore<TestKey, String> { fetcher { "value-for-${it.canonicalId()}" } }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            val data = assertIs<StoreResult.Data<String>>(awaitItem())
            assertEquals("value-for-1", data.value)
            assertEquals(Origin.FETCHER, data.origin)
            expectNoEvents() // live, not completed (FS-1)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // (b1) fetcher throws -> stream emits Error and stays live (FS-5: stream never throws)
    @Test
    fun fetcherThrows_streamEmitsErrorAndStaysLive() = runTest {
        val store = testStore<TestKey, String> { fetcher { throw IllegalStateException("boom") } }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            val error = assertIs<StoreResult.Error>(awaitItem())
            assertIs<StoreError.Fetch>(error.error)
            expectNoEvents() // Error did not terminate the flow
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    // (b2) fetcher throws -> get throws StoreException carrying StoreError.Fetch (FS-2/FS-5)
    @Test
    fun fetcherThrows_getThrowsStoreException() = runTest {
        val store = testStore<TestKey, String> { fetcher { throw IllegalStateException("boom") } }
        val ex = assertFailsWith<StoreException> { store.get(TestKey("1")) }
        assertIs<StoreError.Fetch>(ex.error)
        store.close()
    }

    // (c) single-flight smoke: two concurrent collectors, one fetcher invocation. C-01/C-02 seed.
    @Test
    fun twoConcurrentCollectors_singleFetcherInvocation() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcher {
                calls++
                gate.await()
                "v"
            }
        }
        turbineScope {
            val a = store.stream(TestKey("1")).testIn(backgroundScope)
            val b = store.stream(TestKey("1")).testIn(backgroundScope)
            assertIs<StoreResult.Loading>(a.awaitItem())
            assertIs<StoreResult.Loading>(b.awaitItem()) // both subscribed before the fetch resolves
            gate.complete(Unit)
            assertEquals("v", assertIs<StoreResult.Data<String>>(a.awaitItem()).value)
            assertEquals("v", assertIs<StoreResult.Data<String>>(b.awaitItem()).value)
            assertEquals(1, calls)
        }
        store.close()
    }

    // (d) pins the 001 get-posture: a resident value is served without a refetch (validator arrives in 004)
    @Test
    fun getAfterStreamCommitted_servesResidentValueWithoutRefetch() = runTest {
        var calls = 0
        val store = testStore<TestKey, String> {
            fetcher {
                calls++
                "v$calls"
            }
        }
        store.stream(TestKey("1")).test {
            assertIs<StoreResult.Loading>(awaitItem())
            assertIs<StoreResult.Data<String>>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("v1", store.get(TestKey("1"))) // resident value served
        assertEquals(1, calls) // no second fetch
        store.close()
    }

    // (e) pins replay semantics: a late collector gets Data immediately, never a spurious Loading
    @Test
    fun lateCollectorAfterData_receivesDataImmediately() = runTest {
        val store = testStore<TestKey, String> { fetcher { "v" } }
        assertEquals("v", store.get(TestKey("1"))) // commits residence
        store.stream(TestKey("1")).test {
            val first = assertIs<StoreResult.Data<String>>(awaitItem()) // no Loading first
            assertEquals("v", first.value)
            cancelAndIgnoreRemainingEvents()
        }
        store.close()
    }

    @Test
    fun closeDuringFetch_getWaiterTerminatesPromptly() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcher {
                started.complete(Unit)
                gate.await()
                "v"
            }
        }
        val waiter = backgroundScope.async { runCatching { store.get(TestKey("1")) } }
        started.await()

        store.close()

        val failure =
            withContext(Dispatchers.Default) {
                waiter.await()
            }.exceptionOrNull()
        assertIs<CancellationException>(failure)
    }

    @Test
    fun closeDuringFetch_streamCollectorTerminatesPromptly() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcher {
                started.complete(Unit)
                gate.await()
                "v"
            }
        }
        val collector = backgroundScope.async {
            runCatching { store.stream(TestKey("1")).collect() }
        }
        started.await()

        store.close()

        val failure =
            withContext(Dispatchers.Default) {
                collector.await()
            }.exceptionOrNull()
        assertIs<CancellationException>(failure)
    }

    @Test
    fun getAfterClose_failsFastWithDeterministicException() = runTest {
        val store = testStore<TestKey, String> { fetcher { "v" } }
        store.close()

        val failure = assertFailsWith<IllegalStateException> {
            withTimeout(1_000) { store.get(TestKey("1")) }
        }

        assertEquals("Store is closed.", failure.message)
    }

    @Test
    fun streamAfterClose_failsFastWithDeterministicException() = runTest {
        val store = testStore<TestKey, String> { fetcher { "v" } }
        store.close()

        val failure = assertFailsWith<IllegalStateException> {
            store.stream(TestKey("1"))
        }

        assertEquals("Store is closed.", failure.message)
    }

    @Test
    fun streamCreatedBeforeClose_failsFastWhenCollectedAfterClose() = runTest {
        val store = testStore<TestKey, String> { fetcher { "v" } }
        val stream = store.stream(TestKey("1"))
        store.close()

        val failure = assertFailsWith<IllegalStateException> {
            withTimeout(1_000) { stream.collect() }
        }

        assertEquals("Store is closed.", failure.message)
    }

    @Test
    fun nonCooperativeFetcher_closeTerminatesGetBeforeFetcherReleases() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcher {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                "v"
            }
        }
        val waiter = backgroundScope.async { runCatching { store.get(TestKey("1")) } }

        try {
            started.await()
            store.close()

            val failure =
                withContext(Dispatchers.Default) {
                    waiter.await()
                }.exceptionOrNull()
            assertFalse(release.isCompleted)
            assertIs<CancellationException>(failure)
        } finally {
            release.complete(Unit)
            store.close()
        }
    }

    @Test
    fun nonCooperativeFetcher_closeTerminatesStreamBeforeFetcherReleases() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = testStore<TestKey, String> {
            fetcher {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                "v"
            }
        }
        val collector = backgroundScope.async {
            runCatching { store.stream(TestKey("1")).collect() }
        }

        try {
            started.await()
            store.close()

            val failure =
                withContext(Dispatchers.Default) {
                    collector.await()
                }.exceptionOrNull()
            assertFalse(release.isCompleted)
            assertIs<CancellationException>(failure)
        } finally {
            release.complete(Unit)
            store.close()
        }
    }
}

// 017 residual-deadline repair: Turbine's 3s default nested inside the 25s shadow; raise the
// Turbine deadline above the shadow so runTest provides the only effective timeout (D0, PR #15).
private val TEST_TIMEOUT = 25.seconds
private val TURBINE_DEADLINE = 30.seconds // strictly > TEST_TIMEOUT: the shadow must fire first

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = TEST_TIMEOUT) {
        val scope = this
        withTurbineTimeout(TURBINE_DEADLINE) { scope.testBody() }
    }
