@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.room

import app.cash.turbine.test
import app.cash.turbine.withTurbineTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.FetchPlan
import org.mobilenativefoundation.store6.core.seam.FreshnessContext
import org.mobilenativefoundation.store6.core.seam.FreshnessValidator
import org.mobilenativefoundation.store6.testing.FakeFetcher
import org.mobilenativefoundation.store6.testing.TestWallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

internal class RoomStoreSubstitutionConformanceTest {
    private val users = StoreNamespace("users")
    private val teams = StoreNamespace("teams")
    private val keyA = RoomKitKey(users, "a")
    private val keyB = RoomKitKey(users, "b")
    private val otherNamespaceKey = RoomKitKey(teams, "a")

    /** A cold public Store stream persists its fetched value through Room. */
    @Test
    fun coldStream_loadingThenFetcherData_rowPersisted(): TestResult = runTest {
        val database = createTestDatabase()
        val rig = Rig(database)
        val store = rig.store()
        rig.fetcher.enqueue(keyA, FetcherResult.Success("v1"))
        try {
            store.stream(keyA).test {
                assertIs<StoreResult.Loading>(awaitItem())
                val data = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v1", data.value)
                assertEquals(Origin.FETCHER, data.origin)
                assertFalse(data.isStale)
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(
                "v1",
                database
                    .kitRowDao()
                    .row(keyA.namespace.value, keyA.canonicalId())
                    .first()
                    ?.payload,
            )
        } finally {
            store.closeAndSettleForTest()
            database.close()
        }
    }

    /** A fresh durable row and sidecar let a new Store skip fetching. */
    @Test
    fun secondStoreOverSameDatabase_servesSotWithoutFetch(): TestResult = runTest {
        val database = createTestDatabase()
        val sharedClock = TestWallClock()
        val rigA = Rig(database, sharedClock)
        val storeA = rigA.store()
        var storeB: Store<RoomKitKey, String>? = null
        try {
            rigA.fetcher.enqueue(keyA, FetcherResult.Success("v1", etag = "e1"))
            assertEquals("v1", storeA.get(keyA))
            storeA.closeAndSettleForTest()

            val rigB = Rig(database, sharedClock)
            storeB = rigB.store()
            assertEquals("v1", storeB.get(keyA))
            assertTrue(rigB.fetcher.invocations.isEmpty())
        } finally {
            storeB?.closeAndSettleForTest()
            storeA.closeAndSettleForTest()
            database.close()
        }
    }

    /** Disk hydration refetches unconditionally, then same-engine ETag reuse is conditional. */
    @Test
    fun durableEtag_roundTrips_asConditionalFetch_revalidated(): TestResult = runTest {
        val database = createTestDatabase()
        val sharedClock = TestWallClock()
        val rigA = Rig(database, sharedClock)
        val storeA = rigA.store()
        val conditionalGate = FetchGate(blockFromInvocation = 2)
        var storeB: Store<RoomKitKey, String>? = null
        try {
            rigA.fetcher.enqueue(keyA, FetcherResult.Success("v1", etag = "e1"))
            assertEquals("v1", storeA.get(keyA))
            storeA.closeAndSettleForTest()

            val rigB = Rig(database, sharedClock)
            val durableStatus = assertNotNull(rigB.bookkeeper().status(keyA))
            assertEquals("e1", durableStatus.meta?.etag)
            assertFalse(durableStatus.durablyStale)
            rigB.fetcher.enqueue(keyA, FetcherResult.Success("v1", etag = "e1"))
            rigB.fetcher.onUnscripted = { _, _ ->
                error("unexpected fetch after the unconditional and conditional requests")
            }
            storeB = rigB.store(conditionalGate)
            assertEquals("v1", storeB.get(keyA, Freshness.LocalOnly))
            assertTrue(rigB.fetcher.invocations.isEmpty())
            storeB.invalidate(keyA)

            assertEquals(
                "v1",
                storeB.get(keyA, Freshness.MaxAge(notOlderThan = 1.seconds)),
            )
            assertEquals(1, rigB.fetcher.invocations.size)
            assertNull(rigB.fetcher.invocations.single().etag)

            rigB.fetcher.enqueue(
                keyA,
                FetcherResult.NotModified(),
                FetcherResult.NotModified(),
            )
            storeB.invalidate(keyA)

            storeB.stream(keyA).test {
                val stale = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v1", stale.value)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)

                conditionalGate.entered.await()
                assertEquals(1, rigB.fetcher.invocations.size)
                conditionalGate.release.complete(Unit)
                while (true) {
                    when (val item = awaitItem()) {
                        is StoreResult.Data -> {
                            assertEquals("v1", item.value)
                            if (item.origin == Origin.FETCHER && !item.isStale) {
                                fail("fresh FETCHER Data must be represented by Revalidated")
                            }
                        }
                        is StoreResult.Revalidated -> break
                        else -> fail("unexpected lifecycle item ${item::class.simpleName}")
                    }
                }
                cancelAndIgnoreRemainingEvents()
            }

            val callsAfterRevalidation = rigB.fetcher.invocations.size
            assertTrue(callsAfterRevalidation in 2..3)
            assertTrue(rigB.fetcher.invocations.drop(1).all { it.etag == "e1" })
            assertEquals("v1", storeB.get(keyA))
            assertEquals(callsAfterRevalidation, rigB.fetcher.invocations.size)
        } finally {
            conditionalGate.release.complete(Unit)
            storeB?.closeAndSettleForTest()
            storeA.closeAndSettleForTest()
            database.close()
        }
    }

    /** A namespace watermark survives Store replacement and forces a refetch. */
    @Test
    fun invalidateNamespace_survivesRestart_forcesRefetch(): TestResult = runTest {
        val database = createTestDatabase()
        val sharedClock = TestWallClock()
        val rigA = Rig(database, sharedClock)
        val storeA = rigA.store()
        val refetchGate = FetchGate()
        var storeB: Store<RoomKitKey, String>? = null
        try {
            rigA.fetcher.enqueue(keyA, FetcherResult.Success("v1"))
            assertEquals("v1", storeA.get(keyA))
            storeA.invalidateNamespace(users)
            storeA.closeAndSettleForTest()

            val rigB = Rig(database, sharedClock)
            rigB.fetcher.enqueue(keyA, FetcherResult.Success("v2"))
            storeB = rigB.store(refetchGate)
            storeB.stream(keyA).test {
                val initial = awaitItem()
                val stale =
                    if (initial is StoreResult.Loading) {
                        assertIs<StoreResult.Data<String>>(awaitItem())
                    } else {
                        assertIs<StoreResult.Data<String>>(initial)
                    }
                assertEquals("v1", stale.value)
                assertEquals(Origin.SOT, stale.origin)
                assertTrue(stale.isStale)
                assertTrue(stale.refreshing)

                refetchGate.entered.await()
                refetchGate.release.complete(Unit)
                while (true) {
                    val item = awaitItem()
                    if (item is StoreResult.Data<String> && item.value == "v2") {
                        assertEquals(Origin.FETCHER, item.origin)
                        assertFalse(item.isStale)
                        break
                    }
                    val replay = assertIs<StoreResult.Data<String>>(item)
                    assertEquals("v1", replay.value)
                    assertTrue(replay.isStale)
                }
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(1, rigB.fetcher.invocations.size)
        } finally {
            refetchGate.release.complete(Unit)
            storeB?.closeAndSettleForTest()
            storeA.closeAndSettleForTest()
            database.close()
        }
    }

    /** Clear removes both the user row and its durable freshness record. */
    @Test
    fun clear_deletesRowAndForgetsBookkeeping(): TestResult = runTest {
        val database = createTestDatabase()
        val rig = Rig(database)
        val store = rig.store()
        try {
            rig.fetcher.enqueue(keyA, FetcherResult.Success("v1", etag = "e1"))
            assertEquals("v1", store.get(keyA))
            assertNotNull(rig.bookkeeper().status(keyA))

            store.clear(keyA)

            assertNull(
                database
                    .kitRowDao()
                    .row(keyA.namespace.value, keyA.canonicalId())
                    .first(),
            )
            assertNull(rig.bookkeeper().status(keyA))

            rig.fetcher.enqueue(keyA, FetcherResult.Success("v2"))
            assertEquals("v2", store.get(keyA))
            assertEquals(2, rig.fetcher.invocations.size)
        } finally {
            store.closeAndSettleForTest()
            database.close()
        }
    }

    /** clearNamespace runs the user delete and sweeps matching durable metadata only. */
    @Test
    fun clearNamespace_runsUserStatementAndSweeps(): TestResult = runTest {
        val database = createTestDatabase()
        val rig = Rig(database)
        val store = rig.store()
        try {
            rig.fetcher.enqueue(keyA, FetcherResult.Success("value-a"))
            rig.fetcher.enqueue(keyB, FetcherResult.Success("value-b"))
            rig.fetcher.enqueue(otherNamespaceKey, FetcherResult.Success("value-other"))
            assertEquals("value-a", store.get(keyA))
            assertEquals("value-b", store.get(keyB))
            assertEquals("value-other", store.get(otherNamespaceKey))

            store.clearNamespace(users)

            val dao = database.kitRowDao()
            assertNull(dao.row(users.value, keyA.canonicalId()).first())
            assertNull(dao.row(users.value, keyB.canonicalId()).first())
            assertEquals(
                "value-other",
                dao.row(teams.value, otherNamespaceKey.canonicalId()).first()?.payload,
            )
            val bookkeeper = rig.bookkeeper()
            assertNull(bookkeeper.status(keyA))
            assertNull(bookkeeper.status(keyB))
            assertNotNull(bookkeeper.status(otherNamespaceKey))
        } finally {
            store.closeAndSettleForTest()
            database.close()
        }
    }

    /** StaleIfError serves residence, reports failure, and remains causal-live. */
    @Test
    fun staleIfError_servesStaleAndErrorStaysLive(): TestResult = runTest {
        val database = createTestDatabase()
        val rig = Rig(database)
        val recoveryGate = FetchGate(blockFromInvocation = 3)
        val store = rig.store(recoveryGate)
        val boom = IllegalStateException("boom")
        try {
            rig.fetcher.enqueue(
                keyA,
                FetcherResult.Success("v1"),
                FetcherResult.Error(boom),
                FetcherResult.Success("v2"),
            )
            assertEquals("v1", store.get(keyA))
            store.invalidate(keyA)

            store.stream(keyA, Freshness.StaleIfError).test {
                val stale = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v1", stale.value)
                assertTrue(stale.isStale)

                var terminal = awaitItem()
                var queuedStaleReplays = 0
                while (terminal is StoreResult.Data<*>) {
                    queuedStaleReplays += 1
                    assertTrue(
                        queuedStaleReplays <= 1,
                        "more than one queued stale replay preceded the error",
                    )
                    assertEquals("v1", terminal.value)
                    assertTrue(terminal.isStale)
                    terminal = awaitItem()
                }
                val failure = assertIs<StoreResult.Error>(terminal)
                val fetch = assertIs<StoreError.Fetch>(failure.error)
                assertTrue(fetch.cause === boom)
                assertTrue(failure.servedStale)

                store.clear(keyA)
                var afterClear = awaitItem()
                var queuedPreClearReplays = 0
                while (afterClear !is StoreResult.Loading) {
                    queuedPreClearReplays += 1
                    assertTrue(
                        queuedPreClearReplays <= 1,
                        "more than one queued pre-clear replay preceded Loading",
                    )
                    assertEquals(
                        "v1",
                        assertIs<StoreResult.Data<String>>(afterClear).value,
                    )
                    afterClear = awaitItem()
                }
                recoveryGate.entered.await()
                recoveryGate.release.complete(Unit)
                val recovered = assertIs<StoreResult.Data<String>>(awaitItem())
                assertEquals("v2", recovered.value)
                assertEquals(Origin.FETCHER, recovered.origin)
                assertFalse(recovered.isStale)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(3, rig.fetcher.invocations.size)
        } finally {
            recoveryGate.release.complete(Unit)
            store.closeAndSettleForTest()
            database.close()
        }
    }

    /** A remote deletion cannot satisfy MustBeFresh on a missing row. */
    @Test
    fun mustBeFresh_missingRow_throwsStoreException(): TestResult = runTest {
        val database = createTestDatabase()
        val rig = Rig(database)
        val store = rig.store()
        try {
            rig.fetcher.enqueue(keyA, FetcherResult.Deleted)

            val failure =
                assertFailsWith<StoreException> {
                    store.get(keyA, Freshness.MustBeFresh)
                }

            assertIs<StoreError.Missing>(failure.error)
            assertEquals(1, rig.fetcher.invocations.size)
        } finally {
            store.closeAndSettleForTest()
            database.close()
        }
    }

    /** LocalOnly hydrates a user-seeded Room row without fetching. */
    @Test
    fun localOnly_servesSeededRowWithoutFetch(): TestResult = runTest {
        val database = createTestDatabase()
        val rig = Rig(database)
        val store = rig.store()
        try {
            database.kitRowDao().upsert(row(keyA, "seeded"))

            assertEquals("seeded", store.get(keyA, Freshness.LocalOnly))
            assertTrue(rig.fetcher.invocations.isEmpty())
        } finally {
            store.closeAndSettleForTest()
            database.close()
        }
    }

    /** Concurrent public gets share one cold-key fetch. */
    @Test
    fun concurrentGets_singleFlight(): TestResult = runTest {
        val database = createTestDatabase()
        val rig = Rig(database)
        val fetchGate = FetchGate()
        val planningWitness = ColdFetchPlanningWitness()
        val store = rig.store(fetchGate, planningWitness)
        var firstRequest: Deferred<String>? = null
        var secondRequest: Deferred<String>? = null
        try {
            rig.fetcher.enqueue(keyA, FetcherResult.Success("v1"))

            firstRequest =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.get(keyA)
                }
            // A cold get plans once before reservation and again while reservation holds the
            // key-state lock. Consuming both first-get tokens prevents witness misattribution.
            planningWitness.awaitPlan()
            planningWitness.awaitPlan()
            fetchGate.entered.await()
            secondRequest =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    store.get(keyA)
                }
            planningWitness.awaitPlan()
            // The second token is emitted under that lock immediately before the Join transition.
            planningWitness.awaitPlan()
            val activeFirstRequest = checkNotNull(firstRequest)
            val activeSecondRequest = checkNotNull(secondRequest)
            assertFalse(activeFirstRequest.isCompleted)
            assertFalse(activeSecondRequest.isCompleted)
            fetchGate.release.complete(Unit)
            val values = listOf(activeFirstRequest, activeSecondRequest).awaitAll()

            assertEquals(listOf("v1", "v1"), values)
            assertEquals(1, rig.fetcher.invocations.size)
        } finally {
            fetchGate.release.complete(Unit)
            firstRequest?.cancel()
            secondRequest?.cancel()
            store.closeAndSettleForTest()
            firstRequest?.join()
            secondRequest?.join()
            database.close()
        }
    }

    /** MaxAge uses durable write time after Store replacement and withholds stale data. */
    @Test
    fun maxAge_refetchesWhenDurableMetaTooOld(): TestResult = runTest {
        val database = createTestDatabase()
        val sharedClock = TestWallClock(startEpochMillis = 0L)
        val rigA = Rig(database, sharedClock)
        val storeA = rigA.store()
        var storeB: Store<RoomKitKey, String>? = null
        try {
            rigA.fetcher.enqueue(keyA, FetcherResult.Success("v1"))
            assertEquals("v1", storeA.get(keyA))
            sharedClock.advanceBy(2.seconds)
            storeA.closeAndSettleForTest()

            val rigB = Rig(database, sharedClock)
            rigB.fetcher.enqueue(keyA, FetcherResult.Success("v2"))
            storeB = rigB.store()

            assertEquals(
                "v2",
                storeB.get(keyA, Freshness.MaxAge(notOlderThan = 1.seconds)),
            )
            assertEquals(1, rigB.fetcher.invocations.size)
        } finally {
            storeB?.closeAndSettleForTest()
            storeA.closeAndSettleForTest()
            database.close()
        }
    }

    private fun row(
        key: RoomKitKey,
        value: String,
    ): KitRowEntity =
        KitRowEntity(
            namespace = key.namespace.value,
            id = key.canonicalId(),
            payload = value,
        )
}

private class Rig(
    val database: Store6RoomTestDatabase,
    val wallClock: TestWallClock = TestWallClock(),
) {
    val fetcher = FakeFetcher<RoomKitKey, String>()

    fun sourceOfTruth(): RoomSourceOfTruth<RoomKitKey, String> {
        val dao = database.kitRowDao()
        return RoomSourceOfTruth(
            database = database,
            rowReader = { key ->
                dao.row(key.namespace.value, key.canonicalId()).map { it?.payload }
            },
            rowWriter = { key, value ->
                dao.upsert(KitRowEntity(key.namespace.value, key.canonicalId(), value))
            },
            rowDeleter = { key ->
                dao.delete(key.namespace.value, key.canonicalId())
            },
            namespaceDeleter = { namespace ->
                dao.deleteNamespace(namespace.value)
            },
            allDeleter = {
                dao.deleteAll()
            },
        )
    }

    fun bookkeeper(): RoomBookkeeper =
        RoomBookkeeper(database, database.store6BookkeeperDao())

    fun store(
        fetchGate: FetchGate? = null,
        planningValidator: FreshnessValidator? = null,
    ): Store<RoomKitKey, String> {
        val installedFetcher =
            if (fetchGate == null) {
                fetcher
            } else {
                object : Fetcher<RoomKitKey, String> {
                    override suspend fun fetch(
                        key: RoomKitKey,
                        etag: String?,
                    ): FetcherResult<String> {
                        fetchGate.awaitBeforeFetch()
                        return this@Rig.fetcher.fetch(key, etag)
                    }
                }
            }
        return org.mobilenativefoundation.store6.core.store {
            fetcher(installedFetcher)
            persistence(this@Rig.sourceOfTruth())
            bookkeeper(this@Rig.bookkeeper())
            wallClock(this@Rig.wallClock)
            if (planningValidator != null) {
                freshnessValidator(planningValidator)
            }
        }
    }
}

private class ColdFetchPlanningWitness : FreshnessValidator {
    private val plans = Channel<Unit>(capacity = Channel.UNLIMITED)

    override fun plan(context: FreshnessContext): FetchPlan {
        check(context.freshness == Freshness.CachedOrFetch) {
            "single-flight witness requires CachedOrFetch"
        }
        check(!context.hasResidentValue) {
            "single-flight witness requires a cold key"
        }
        check(context.meta == null)
        check(!context.epochStale)
        check(context.status == null)
        check(plans.trySend(Unit).isSuccess)
        return FetchPlan.Fetch(servesResidentWhileFetching = false)
    }

    suspend fun awaitPlan() {
        plans.receive()
    }
}

private class FetchGate(
    private val blockFromInvocation: Int = 1,
) {
    private val lock = Mutex()
    private var invocationCount = 0
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    init {
        require(blockFromInvocation >= 1)
    }

    suspend fun awaitBeforeFetch() {
        val shouldBlock =
            lock.withLock {
                invocationCount += 1
                invocationCount >= blockFromInvocation
            }
        if (shouldBlock) {
            entered.complete(Unit)
            release.await()
        }
    }
}

private suspend fun Store<*, *>.closeAndSettleForTest() {
    close()
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
