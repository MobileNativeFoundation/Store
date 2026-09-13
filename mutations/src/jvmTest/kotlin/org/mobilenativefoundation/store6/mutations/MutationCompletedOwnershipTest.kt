@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.StoreResults
import org.mobilenativefoundation.store6.core.seam.FreshnessEvidence
import org.mobilenativefoundation.store6.core.seam.SourceAdoption
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAckRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationAliasState
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.testing.TestWallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutationCompletedOwnershipTest {
    @Test
    fun manyLargeValuesOnOneKey_releaseCompletedBlobsAfterConfirmedPrune() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val fixture = OwnershipFixture()
        val backend = FakeBackend()
        val engine = fixture.open(storage, backend)
        val key = MutationsTestKey("large-history")
        val events = mutableListOf<MutationEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.eventBus.events.collect { events += it }
        }
        val completed = mutableSetOf<String>()

        repeat(24) { index ->
            val value = "$index:" + ('a' + index).toString().repeat(64 * 1024)
            val id = engine.mutate(key, fixture.set, value)
            completed += id
            engine.drain(key)
            testScheduler.runCurrent()

            storage.assertPrunedThrough((index + 1).toLong())
            val ownership = engine.completedOwnershipSnapshot()
            ownership.assertReleased(completed)
            assertEquals(0L, ownership.retainedBlobBytes)
            val lifecycle = events.filterIsInstance<MutationIntentEvent>().filter { it.mutationId == id }
            assertEquals(
                listOf(
                    MutationEnqueued::class,
                    MutationAttempted::class,
                    MutationAcknowledged::class,
                    MutationAdopted::class,
                    MutationEffectApplied::class,
                    MutationRetired::class,
                ),
                lifecycle.map { it::class },
            )
            assertTrue(lifecycle.all { it.identity.canonicalId == key.canonicalId() })
            val retired = lifecycle.filterIsInstance<MutationRetired>().single()
            assertEquals(1, retired.generation)
            assertEquals((index + 1).toLong(), retired.retiredThroughSequence)
        }
        assertEquals(24, backend.receivedPushes.size)
        assertEquals(24, backend.pushedValues.toSet().size)
    }

    @Test
    fun aliasAckSurvivesPruningAndRestart_thenReleasesCompletedOwnership() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val fixture = OwnershipFixture()
        val backend = FakeBackend()
        val source = MutationsTestKey("provisional")
        val target = MutationsTestKey("canonical")
        val handle = OwnershipHandle()
        val first = fixture.open(storage, backend, handle)
        val completed = first.mutate(MutationsTestKey("earlier"), fixture.set, "earlier-value")
        first.drain(MutationsTestKey("earlier"))
        val authoritative = "authoritative:" + "x".repeat(64 * 1024)
        backend.pushBehavior = { _, _ -> MutationPresentAck(authoritative, "canonical-tag", target) }
        handle.applyFailure = IllegalStateException("adoption unavailable")
        val held = first.mutate(source, fixture.set, "provisional-value")

        assertFailsWith<IllegalStateException> { first.drain(source) }

        storage.transaction { transaction ->
            assertEquals(StoredPhase.ACKED, transaction.executions(OWNERSHIP_CLIENT_ID).single().phase)
            assertEquals(MutationAliasState.PENDING, transaction.aliases().single().state)
            assertEquals(authoritative, transaction.acks(OWNERSHIP_CLIENT_ID).single().authoritativeBlob?.decodeToString())
        }
        first.completedOwnershipSnapshot().assertReleased(setOf(completed))
        first.completedOwnershipSnapshot().assertActiveAck(held)

        val resumedHandle = OwnershipHandle()
        val reopened = fixture.open(storage, backend, resumedHandle)
        val events = mutableListOf<MutationEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            reopened.eventBus.events.collect { events += it }
        }
        reopened.pendingWrites()
        reopened.completedOwnershipSnapshot().assertActiveAck(held)
        reopened.drain(source)
        testScheduler.runCurrent()

        assertEquals(2, backend.receivedPushes.size)
        assertEquals(listOf(target.identity() to authoritative), resumedHandle.applied)
        assertEquals(listOf(target.identity()), resumedHandle.staled)
        storage.assertPrunedThrough(2L)
        storage.transaction { assertEquals(MutationAliasState.ACTIVE, it.aliases().single().state) }
        reopened.completedOwnershipSnapshot().assertReleased(setOf(completed, held))
        val retired = events.filterIsInstance<MutationRetired>().single()
        assertEquals(held, retired.mutationId)
        assertEquals(source.canonicalId(), retired.identity.canonicalId)
        assertEquals(1, retired.generation)
        assertEquals(2L, retired.retiredThroughSequence)
    }

    @Test
    fun serverWinsReleasesAttemptAndEffects_afterSkippedAndRetiredEvents() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val fixture = OwnershipFixture()
        val backend = FakeBackend().apply {
            pushBehavior = { _, _ ->
                throw StoreResults.exception(
                    StoreResults.conflict(null, "precondition changed"),
                    IllegalStateException("server conflict"),
                )
            }
        }
        val handle = OwnershipHandle()
        val engine = fixture.open(
            storage,
            backend,
            handle,
            conflicts = MutationConflictRegistration(
                precondition = null,
                merge = { _, _, _ -> MutationConflictResolution.ServerWins },
            ),
        )
        val key = MutationsTestKey("server-wins-history")
        val events = mutableListOf<MutationEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.eventBus.events.collect { events += it }
        }
        val id = engine.mutate(key, fixture.set, "mine:" + "m".repeat(64 * 1024))
        engine.drain(key)
        storage.transaction { assertEquals(StoredPhase.REFRESH_REQUIRED, it.executions(OWNERSHIP_CLIENT_ID).single().phase) }

        engine.drain(key)
        testScheduler.runCurrent()

        storage.assertPrunedThrough(1L)
        engine.completedOwnershipSnapshot().assertReleased(setOf(id))
        assertEquals(0L, engine.completedOwnershipSnapshot().retainedBlobBytes)
        assertTrue(handle.applied.isEmpty())
        assertTrue(handle.staled.isEmpty())
        assertEquals(1, backend.receivedPushes.size)
        val skipped = events.filterIsInstance<MutationEffectSkipped>().single()
        assertEquals(id, skipped.mutationId)
        assertEquals(1, skipped.generation)
        assertEquals(0, skipped.effectIndex)
        val retired = events.filterIsInstance<MutationRetired>().single()
        assertEquals(id, retired.mutationId)
        assertEquals(key.canonicalId(), retired.identity.canonicalId)
        assertEquals(1L, retired.retiredThroughSequence)
        assertTrue(events.indexOf(skipped) < events.indexOf(retired))
    }

    @Test
    fun reopenedRetiredHistory_releasesOwnershipWhenCheckpointBecomesConfirmed() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val fixture = OwnershipFixture()
        val backend = FakeBackend().apply {
            retireBehavior = { MutationRetirementAck(0L) }
        }
        val first = fixture.open(storage, backend)
        val key = MutationsTestKey("unconfirmed-history")
        val id = first.mutate(key, fixture.set, "unconfirmed:" + "u".repeat(64 * 1024))
        first.drain(key)
        storage.transaction { transaction ->
            assertEquals(StoredPhase.RETIRED, transaction.executions(OWNERSHIP_CLIENT_ID).single().phase)
            assertEquals(1L, transaction.client(OWNERSHIP_CLIENT_ID)?.retiredThroughSequence)
            assertEquals(0L, transaction.client(OWNERSHIP_CLIENT_ID)?.serverConfirmedRetiredThroughSequence)
        }
        backend.retireBehavior = { MutationRetirementAck(it.retiredThroughSequence) }
        val reopened = fixture.open(storage, backend)

        reopened.drain()

        assertEquals(1, backend.receivedPushes.size)
        assertEquals(listOf(1L, 1L), backend.retirementRequests.map { it.retiredThroughSequence })
        storage.assertPrunedThrough(1L)
        reopened.completedOwnershipSnapshot().assertReleased(setOf(id))
        assertEquals(0L, reopened.completedOwnershipSnapshot().retainedBlobBytes)
    }

    @Test
    fun completedCleanup_preservesParkedEvidenceAndUnconfirmedRetirementGap() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val fixture = OwnershipFixture()
        val backend = FakeBackend()
        val codec = object : MutationCodec<String> {
            override fun encode(value: String): ByteArray {
                check(value != "bad") { "unencodable value" }
                return value.encodeToByteArray()
            }

            override fun decode(version: Int, bytes: ByteArray): String = bytes.decodeToString()
        }
        val engine = fixture.open(storage, backend, valueCodec = codec)
        val key = MutationsTestKey("parked-history")
        val parked = engine.mutate(key, fixture.set, "bad")
        val completed = engine.mutate(key, fixture.set, "good:" + "g".repeat(64 * 1024))

        engine.drain(key)

        assertEquals(parked, engine.deadLetters().single().mutationId)
        assertEquals(MutationFailureKind.CODEC, engine.deadLetters().single().failure.kind)
        assertTrue(parked in engine.completedOwnershipSnapshot().mutationIdsByCache.getValue("durableExecutions"))
        engine.completedOwnershipSnapshot().assertReleased(setOf(completed))
        storage.transaction { transaction ->
            assertEquals(0L, transaction.client(OWNERSHIP_CLIENT_ID)?.retiredThroughSequence)
            assertEquals(0L, transaction.client(OWNERSHIP_CLIENT_ID)?.serverConfirmedRetiredThroughSequence)
            assertEquals(setOf(StoredPhase.PARKED, StoredPhase.RETIRED), transaction.executions(OWNERSHIP_CLIENT_ID).map { it.phase }.toSet())
            assertEquals(1, transaction.failures(OWNERSHIP_CLIENT_ID).size)
        }
        val reopened = fixture.open(storage, backend, valueCodec = codec)
        assertEquals(parked, reopened.deadLetters().single().mutationId)
        reopened.mutate(key, fixture.set, "after-restart")
        reopened.drain(key)
        assertTrue(backend.receivedPushes.all { it.retiredThroughSequence == 0L })
        assertTrue(backend.retirementRequests.isEmpty())
        assertEquals(parked, reopened.deadLetters().single().mutationId)
    }

    @Test
    fun concurrentPruneDuringRetirementPublication_preservesActiveOwnerAndEvent() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val fixture = OwnershipFixture()
        val backend = FakeBackend()
        val heldKey = MutationsTestKey("held", StoreNamespace("held"))
        val contender = MutationsTestKey("contender", heldKey.namespace)
        val handle = OwnershipHandle()
        val adoptionEntered = CompletableDeferred<Unit>()
        val releaseAdoption = CompletableDeferred<Unit>()
        handle.beforeApply = { key ->
            if (key.identity() == heldKey.identity()) {
                adoptionEntered.complete(Unit)
                releaseAdoption.await()
            }
        }
        val engine = fixture.open(storage, backend, handle)
        val retiringKey = MutationsTestKey("retiring")
        val retiring = engine.mutate(retiringKey, fixture.set, "retiring-value")
        val held = engine.mutate(heldKey, fixture.set, "held-value")
        val waiting = engine.mutate(contender, fixture.set, "contender-value")
        val heldDrain = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { engine.drain(heldKey) }
        adoptionEntered.await()
        engine.completedOwnershipSnapshot().assertActiveAck(held)

        val blocker = MutationsTestKey("signal-blocker", StoreNamespace("signals"))
        val blocked = CompletableDeferred<Unit>()
        val releaseSignal = CompletableDeferred<Unit>()
        val events = mutableListOf<MutationEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.eventBus.events.collect { events += it }
        }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.changes.collect { key ->
                if (key.identity() == blocker.identity()) {
                    blocked.complete(Unit)
                    releaseSignal.await()
                }
            }
        }
        engine.mutate(blocker, fixture.set, "blocker")
        testScheduler.runCurrent()
        blocked.await()
        val retirement = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { engine.drain(retiringKey) }
        assertFalse(retirement.isCompleted)
        storage.transaction { transaction ->
            assertEquals(StoredPhase.RETIRED, transaction.executions(OWNERSHIP_CLIENT_ID).single { it.clientSequence == 1L }.phase)
        }

        engine.drain(MutationsTestKey("checkpoint-only", StoreNamespace("checkpoint")))
        engine.drain(contender)
        engine.completedOwnershipSnapshot().assertActiveAck(held)
        assertEquals(listOf(heldKey.identity(), retiringKey.identity()), backend.receivedPushes.map { it.key.identity() })
        storage.transaction { transaction ->
            assertFalse(transaction.intents(OWNERSHIP_CLIENT_ID).any { it.mutationId == retiring })
            assertEquals(StoredPhase.ACKED, transaction.executions(OWNERSHIP_CLIENT_ID).single { it.clientSequence == 2L }.phase)
        }
        assertTrue(events.filterIsInstance<MutationRetired>().none { it.mutationId == retiring })

        releaseSignal.complete(Unit)
        testScheduler.runCurrent()
        retirement.await()
        engine.completedOwnershipSnapshot().assertReleased(setOf(retiring))
        engine.completedOwnershipSnapshot().assertActiveAck(held)
        val event = events.filterIsInstance<MutationRetired>().single { it.mutationId == retiring }
        assertEquals(retiringKey.canonicalId(), event.identity.canonicalId)
        assertEquals(1, event.generation)
        assertEquals(1L, event.retiredThroughSequence)

        releaseAdoption.complete(Unit)
        testScheduler.runCurrent()
        heldDrain.await()
        engine.drain(contender)
        engine.completedOwnershipSnapshot().assertReleased(setOf(retiring, held, waiting))
    }
}

private const val OWNERSHIP_CLIENT_ID = "ownership-client"

private class OwnershipFixture {
    lateinit var set: MutatorRef<MutationsTestKey, String, String>
    private val registry = mutatorRegistry<MutationsTestKey, String> {
        set = upsert(
            id = "ownership-set",
            version = 1,
            codec = FixtureStringArgsCodec,
            stales = { key, _ -> StaleSet(keys = setOf(key), namespaces = emptySet()) },
        ) { _, value -> MutationPresence.Present(value) }
    }

    fun open(
        storage: MutationJournalStorage,
        backend: FakeBackend,
        handle: OwnershipHandle = OwnershipHandle(),
        conflicts: MutationConflictRegistration<MutationsTestKey, String>? = null,
        valueCodec: MutationCodec<String> = FixtureStringArgsCodec,
    ): MutationEngine<MutationsTestKey, String> =
        MutationEngine(
            registry = registry,
            server = backend,
            journal = StorageBackedMutationJournal(
                storage = storage,
                registrations = registry.registrations,
                clientId = OWNERSHIP_CLIENT_ID,
                hydrateOnFirstUse = true,
            ),
            keyResolver = MutationKeyResolver { identity ->
                MutationsTestKey(identity.canonicalId, StoreNamespace(identity.namespace))
            },
            valueCodecVersion = 1,
            valueCodec = valueCodec,
            conflicts = conflicts,
            baseReader = { "base" },
            clientId = OWNERSHIP_CLIENT_ID,
            wallClock = TestWallClock(100L),
        ).also { it.bind(handle) }
}

private class OwnershipHandle : StoreWriteHandle<MutationsTestKey, String> {
    var applyFailure: Throwable? = null
    var beforeApply: suspend (MutationsTestKey) -> Unit = {}
    val applied = mutableListOf<Pair<KeyIdentity, String>>()
    val staled = mutableListOf<KeyIdentity>()

    override suspend fun applyAcknowledgement(
        key: MutationsTestKey,
        value: String,
        etag: String?,
        freshnessEvidence: FreshnessEvidence?,
        adoption: SourceAdoption?,
    ) {
        apply(key, value)
    }

    override suspend fun apply(key: MutationsTestKey, value: String) {
        beforeApply(key)
        applyFailure?.let { throw it }
        applied += key.identity() to value
    }

    override suspend fun markStale(key: MutationsTestKey) {
        staled += key.identity()
    }

    override suspend fun confirmFresh(key: MutationsTestKey, etag: String?) = Unit
}

private suspend fun MutationJournalStorage.assertPrunedThrough(sequence: Long) {
    transaction { transaction ->
        val client = assertNotNull(transaction.client(OWNERSHIP_CLIENT_ID))
        assertEquals(sequence, client.retiredThroughSequence)
        assertEquals(sequence, client.serverConfirmedRetiredThroughSequence)
        assertTrue(transaction.intents(OWNERSHIP_CLIENT_ID).isEmpty())
        assertTrue(transaction.executions(OWNERSHIP_CLIENT_ID).isEmpty())
        assertTrue(transaction.attempts(OWNERSHIP_CLIENT_ID).isEmpty())
        assertTrue(transaction.acks(OWNERSHIP_CLIENT_ID).isEmpty())
        assertTrue(transaction.effects(OWNERSHIP_CLIENT_ID).isEmpty())
    }
}

private data class CompletedOwnershipSnapshot(
    val mutationIdsByCache: Map<String, Set<String>>,
    val retainedBlobBytes: Long,
) {
    fun assertReleased(ids: Set<String>) {
        mutationIdsByCache.forEach { (cache, retained) ->
            assertTrue((retained intersect ids).isEmpty(), "$cache retains completed mutations ${retained intersect ids}")
        }
    }

    fun assertActiveAck(id: String) {
        for (cache in listOf("durableExecutions", "durableAttempts", "durableAcks", "durableEffectRows", "effectSnapshots")) {
            assertTrue(id in mutationIdsByCache.getValue(cache), "$cache lost active acknowledgement $id")
        }
        assertTrue(retainedBlobBytes > 0L)
    }
}

private fun MutationEngine<*, *>.completedOwnershipSnapshot(): CompletedOwnershipSnapshot {
    val caches = listOf(
        "durableExecutions",
        "durableAttempts",
        "durableAcks",
        "durableEffectRows",
        "effectSnapshots",
        "phases",
        "completedAttempts",
        "acceptedIntentIdentities",
        "preAckParkCandidates",
        "legacyPendingPresentAcks",
        "preparedAcknowledgements",
        "acknowledgedProjections",
    )
    val idsByCache = linkedMapOf<String, Set<String>>()
    var blobBytes = 0L
    for (cache in caches) {
        val field = javaClass.getDeclaredField(cache).apply { isAccessible = true }
        val map = field.get(this)
        val state = map.javaClass.getDeclaredField("state").apply { isAccessible = true }.get(map) as StateFlow<*>
        val entries = state.value as Map<*, *>
        idsByCache[cache] = entries.keys.map { it as String }.toSet()
        entries.values.forEach { row ->
            blobBytes += when (row) {
                is MutationAttemptRecord -> (row.baseBlob?.size ?: 0).toLong() + (row.mineBlob?.size ?: 0)
                is MutationAckRecord -> (row.authoritativeBlob?.size ?: 0).toLong()
                else -> 0L
            }
        }
    }
    return CompletedOwnershipSnapshot(idsByCache, blobBytes)
}

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
