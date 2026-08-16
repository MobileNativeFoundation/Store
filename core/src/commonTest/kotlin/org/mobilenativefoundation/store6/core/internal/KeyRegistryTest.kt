package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.FakeWallClock
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.TestKey
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateStoreApi::class, ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)
class KeyRegistryTest {
    @Test
    fun withEngine_createsOnce_reusesResident() = runTest {
        val factory = TrackingEngineFactory(backgroundScope)
        val registry = registry(maxIdle = 1, factory)
        val observed = mutableListOf<KeyEngine<TestKey, String>>()

        registry.withEngine(TestKey("a")) { engine -> observed += engine }
        registry.withEngine(TestKey("a")) { engine -> observed += engine }

        assertEquals(1, factory.created.size)
        assertSame(observed[0], observed[1])
        assertEquals(1L, registry.createdCountForTest())
    }

    @Test
    fun release_atZeroRefs_parksQuiescentEngineInIdle() = runTest {
        val factory = TrackingEngineFactory(backgroundScope)
        val registry = registry(maxIdle = 1, factory)

        registry.withEngine(TestKey("a")) {
            assertEquals(1, registry.residentCountForTest())
            assertEquals(0, registry.idleCountForTest())
        }

        assertEquals(1, registry.residentCountForTest())
        assertEquals(1, registry.idleCountForTest())
        assertFalse(factory.only("a").job.isCancelled)
    }

    @Test
    fun idleOverflow_evictsEldestOnly() = runTest {
        val factory = TrackingEngineFactory(backgroundScope)
        val registry = registry(maxIdle = 2, factory)

        registry.withEngine(TestKey("a")) {}
        registry.withEngine(TestKey("b")) {}
        registry.withEngine(TestKey("c")) {}

        assertTrue(factory.only("a").job.isCancelled)
        assertFalse(factory.only("b").job.isCancelled)
        assertFalse(factory.only("c").job.isCancelled)
        assertEquals(2, registry.residentCountForTest())
        assertEquals(2, registry.idleCountForTest())
        assertEquals(1L, registry.destroyedCountForTest())
    }

    @Test
    fun reacquire_promotesFromIdle_andRefreshesLruPosition() = runTest {
        val factory = TrackingEngineFactory(backgroundScope)
        val registry = registry(maxIdle = 2, factory)
        var firstA: KeyEngine<TestKey, String>? = null

        registry.withEngine(TestKey("a")) { engine -> firstA = engine }
        registry.withEngine(TestKey("b")) {}
        registry.withEngine(TestKey("a")) { engine -> assertSame(firstA, engine) }
        registry.withEngine(TestKey("c")) {}

        assertFalse(factory.only("a").job.isCancelled)
        assertTrue(factory.only("b").job.isCancelled)
        assertFalse(factory.only("c").job.isCancelled)
        assertEquals(1, factory.forId("a").size)
    }

    @Test
    fun maxIdleZero_destroysAtQuiescence() = runTest {
        val factory = TrackingEngineFactory(backgroundScope)
        val registry = registry(maxIdle = 0, factory)

        registry.withEngine(TestKey("a")) {}

        assertTrue(factory.only("a").job.isCancelled)
        assertEquals(0, registry.residentCountForTest())
        assertEquals(0, registry.idleCountForTest())
        assertEquals(1L, registry.createdCountForTest())
        assertEquals(1L, registry.destroyedCountForTest())
    }

    @Test
    fun fetchResidencyHook_pinsEngineAcrossCallerRelease() = runTest {
        val factory = TrackingEngineFactory(backgroundScope)
        val registry = registry(maxIdle = 0, factory)
        val callerEntered = CompletableDeferred<Unit>()
        val releaseCaller = CompletableDeferred<Unit>()
        val caller =
            async(start = CoroutineStart.UNDISPATCHED) {
                registry.withEngine(TestKey("a")) {
                    callerEntered.complete(Unit)
                    releaseCaller.await()
                }
            }
        callerEntered.await()
        val created = factory.only("a")

        created.hooks.retainFetchRef()
        releaseCaller.complete(Unit)
        caller.await()

        assertEquals(1, registry.residentCountForTest())
        assertEquals(0L, registry.destroyedCountForTest())
        assertFalse(created.job.isCancelled)

        created.hooks.releaseFetchRef()
        assertEquals(0, registry.residentCountForTest())
        assertEquals(1L, registry.destroyedCountForTest())
        assertTrue(created.job.isCancelled)
    }

    @Test
    fun closedRegistry_withEngine_throwsStoreClosed() = runTest {
        val factory = TrackingEngineFactory(backgroundScope)
        val registry = registry(maxIdle = 1, factory)
        registry.clearOnClose()

        val failure =
            assertFailsWith<IllegalStateException> {
                registry.withEngine(TestKey("a")) {}
            }

        assertEquals(STORE_CLOSED_MESSAGE, failure.message)
        assertEquals(0, factory.created.size)
    }

    @Test
    fun sweep_retainsEngines_andReleasesAfterAction() = runTest {
        val factory = TrackingEngineFactory(backgroundScope)
        val registry = registry(maxIdle = 0, factory)
        val releaseHolders = CompletableDeferred<Unit>()
        val heldA = CompletableDeferred<KeyEngine<TestKey, String>>()
        val heldB = CompletableDeferred<KeyEngine<TestKey, String>>()
        val holderA =
            async(start = CoroutineStart.UNDISPATCHED) {
                registry.withEngine(TestKey("a")) { engine ->
                    heldA.complete(engine)
                    releaseHolders.await()
                }
            }
        val holderB =
            async(start = CoroutineStart.UNDISPATCHED) {
                registry.withEngine(TestKey("b")) { engine ->
                    heldB.complete(engine)
                    releaseHolders.await()
                }
            }
        val expected = setOf(heldA.await(), heldB.await())
        val actedOn = mutableSetOf<KeyEngine<TestKey, String>>()
        val actionEntered = CompletableDeferred<Unit>()
        val releaseAction = CompletableDeferred<Unit>()

        val sweep =
            async(start = CoroutineStart.UNDISPATCHED) {
                registry.snapshotAndForEachResident(namespace = null) { engine ->
                    actedOn += engine
                    actionEntered.complete(Unit)
                    releaseAction.await()
                }
            }
        actionEntered.await()
        releaseHolders.complete(Unit)
        holderA.await()
        holderB.await()

        assertEquals(2, registry.residentCountForTest())
        assertEquals(0, registry.idleCountForTest())
        assertEquals(0L, registry.destroyedCountForTest())
        assertFalse(factory.only("a").job.isCancelled)
        assertFalse(factory.only("b").job.isCancelled)

        releaseAction.complete(Unit)
        val snapshot = sweep.await()
        assertEquals(expected, actedOn)
        assertEquals(expected, snapshot.toSet())
        assertEquals(0, registry.residentCountForTest())
        assertEquals(0, registry.idleCountForTest())
        assertEquals(2L, registry.destroyedCountForTest())
        assertTrue(factory.only("a").job.isCancelled)
        assertTrue(factory.only("b").job.isCancelled)
    }

    @Test
    fun sweep_releasesAllRetainedEngines_whenActionThrows() = runTest {
        val factory = TrackingEngineFactory(backgroundScope)
        val registry = registry(maxIdle = 0, factory)
        val releaseHolders = CompletableDeferred<Unit>()
        val heldA = CompletableDeferred<Unit>()
        val heldB = CompletableDeferred<Unit>()
        val holderA =
            async(start = CoroutineStart.UNDISPATCHED) {
                registry.withEngine(TestKey("a")) {
                    heldA.complete(Unit)
                    releaseHolders.await()
                }
            }
        val holderB =
            async(start = CoroutineStart.UNDISPATCHED) {
                registry.withEngine(TestKey("b")) {
                    heldB.complete(Unit)
                    releaseHolders.await()
                }
            }
        heldA.await()
        heldB.await()

        val failure =
            assertFailsWith<IllegalStateException> {
                registry.snapshotAndForEachResident(namespace = null) {
                    throw IllegalStateException("sweep failed")
                }
            }
        assertEquals("sweep failed", failure.message)
        assertEquals(2, registry.residentCountForTest())
        assertEquals(0L, registry.destroyedCountForTest())

        releaseHolders.complete(Unit)
        holderA.await()
        holderB.await()
        assertEquals(0, registry.residentCountForTest())
        assertEquals(2L, registry.destroyedCountForTest())
        assertTrue(factory.only("a").job.isCancelled)
        assertTrue(factory.only("b").job.isCancelled)
    }

    @Test
    fun fetchJob_pinsResidencyAfterLastWaiterCancels_untilCommitSettles() = runTest {
        val key = TestKey("fetch-residency")
        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        var fetchCalls = 0
        val real =
            store<TestKey, String> {
                maxIdleKeys(0)
                fetcher {
                    fetchCalls += 1
                    fetchStarted.complete(Unit)
                    releaseFetch.await()
                    "committed"
                }
            } as RealStore<TestKey, String>
        val waiterEngine = CompletableDeferred<KeyEngine<TestKey, String>>()
        val firstWaiter =
            async(start = CoroutineStart.UNDISPATCHED) {
                real.withEngine(key) { engine ->
                    waiterEngine.complete(engine)
                    engine.get(Freshness.MustBeFresh)
                }
            }
        try {
            fetchStarted.await()
            val engine = waiterEngine.await()
            assertTrue(engine.state.value.fetch is FetchSlot.InFlight)
            firstWaiter.cancelAndJoin()
            assertEquals(1, fetchCalls)
            assertEquals(1, real.residentEngineCountForTest())
            assertEquals(0, real.idleEngineCountForTest())
            assertEquals(1L, real.createdEngineCountForTest())
            assertEquals(0L, real.destroyedEngineCountForTest())

            val fetchSettled =
                async(start = CoroutineStart.UNDISPATCHED) {
                    engine.state.first { state -> state.fetch is FetchSlot.Idle }
                }
            releaseFetch.complete(Unit)
            fetchSettled.await()
            awaitRegistryCounts(real, resident = 0, destroyed = 1L)

            assertEquals("committed", real.get(key, Freshness.CachedOrFetch))
            assertEquals(1, fetchCalls)
            awaitRegistryCounts(real, resident = 0, destroyed = 2L)
            assertEquals(
                real.residentEngineCountForTest().toLong(),
                real.createdEngineCountForTest() - real.destroyedEngineCountForTest(),
            )
        } finally {
            releaseFetch.complete(Unit)
            firstWaiter.cancelAndJoin()
            real.close()
            real.awaitTerminationForTest()
        }
    }

    @Test
    fun counters_createdMinusDestroyed_equalsResident() = runTest {
        val factory = TrackingEngineFactory(backgroundScope)
        val registry = registry(maxIdle = 2, factory)

        repeat(5) { index -> registry.withEngine(TestKey("key-$index")) {} }

        assertEquals(5L, registry.createdCountForTest())
        assertEquals(3L, registry.destroyedCountForTest())
        assertEquals(
            registry.residentCountForTest().toLong(),
            registry.createdCountForTest() - registry.destroyedCountForTest(),
        )
    }

    private fun registry(
        maxIdle: Int,
        factory: TrackingEngineFactory,
    ): KeyRegistry<TestKey, String> = KeyRegistry(maxIdle, factory::create)

    private suspend fun awaitRegistryCounts(
        store: RealStore<TestKey, String>,
        resident: Int,
        destroyed: Long,
    ) {
        // Preserve the real-time Default-dispatch hop and let the suite-level runTest bound own
        // cancellation.
        withContext(Dispatchers.Default) {
            while (
                store.residentEngineCountForTest() != resident ||
                store.destroyedEngineCountForTest() != destroyed
            ) {
                yield()
            }
        }
    }

    private class TrackingEngineFactory(
        private val parentScope: CoroutineScope,
    ) {
        val created = mutableListOf<CreatedEngine>()

        fun create(
            key: TestKey,
            id: KeyId,
            hooks: EngineResidencyHooks,
        ): KeyEngine<TestKey, String> {
            val job = SupervisorJob(parentScope.coroutineContext[Job])
            val engine =
                KeyEngine(
                    key = key,
                    keyId = id,
                    fetcher = ResultFetcher { FetcherResult.Success("unused") },
                    sot = InMemorySourceOfTruth(),
                    bookkeeper = InMemoryBookkeeper(),
                    validator = DefaultFreshnessValidator,
                    wallClock = FakeWallClock(now = 0L),
                    engineScope = CoroutineScope(parentScope.coroutineContext + job),
                    residencyHooks = hooks,
                )
            created += CreatedEngine(id.canonicalId, engine, job, hooks)
            return engine
        }

        fun forId(canonicalId: String): List<CreatedEngine> =
            created.filter { it.canonicalId == canonicalId }

        fun only(canonicalId: String): CreatedEngine = forId(canonicalId).single()
    }

    private data class CreatedEngine(
        val canonicalId: String,
        val engine: KeyEngine<TestKey, String>,
        val job: Job,
        val hooks: EngineResidencyHooks,
    )
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
