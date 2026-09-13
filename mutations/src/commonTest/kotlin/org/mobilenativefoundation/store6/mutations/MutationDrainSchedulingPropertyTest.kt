@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.testing.TestWallClock

class MutationDrainSchedulingPropertyTest {
    @Test
    fun perKeyFifoWithParkedAndDeclinedHeads_neverBlocksOtherKeys_property() = runTest {
        assertGeneratedPerKeySchedulingCases()
        assertSameNamespaceNonownerStartsNoTransportWhileDistinctNamespaceOverlaps()
    }

    @Test
    fun noEntryDrainsAheadOfItsPolicyVerdict_property() = runTest {
        assertGeneratedVerdictOrderingCases()
        assertGlobalDrainSkipsBackoffIneligibleEntry()
        assertGlobalDrainResumesPostAttemptOwnerBeforeEnumeratedNonowner()
    }
}

private suspend fun assertGeneratedPerKeySchedulingCases() {
    val seeds = SchedulingRng(PER_KEY_MASTER_SEED)
    repeat(GENERATED_SCHEDULING_CASES) { caseIndex ->
        val caseSeed = seeds.nextLong()
        val random = SchedulingRng(caseSeed)
        val storage = InMemoryMutationJournalStorage()
        val mutations = SchedulingMutations()
        val backend = retainingSchedulingBackend()
        val handle = SchedulingHandle()
        val clock = TestWallClock(1_000L + caseIndex)
        val engine = openSchedulingEngine(storage, mutations, backend, handle, clock)
        val namespace = StoreNamespace("schedule-$caseIndex")
        val keyCount = 3 + random.nextInt(3)
        val keys = (0 until keyCount).map { keyIndex -> MutationsTestKey("key-$keyIndex", namespace) }
        val expectedPushes = linkedMapOf<KeyIdentity, List<Long>>()
        val parkedIds = mutableSetOf<String>()
        val declinedIds = mutableMapOf<KeyIdentity, List<String>>()
        var nextSequence = 0L

        keys.forEachIndexed { keyIndex, key ->
            val verdict =
                if (keyIndex == 0) SchedulingVerdict.EXECUTE else SchedulingVerdict.entries[random.nextInt(3)]
            when (verdict) {
                SchedulingVerdict.EXECUTE -> {
                    engine.mutate(key, mutations.append, "+$keyIndex-a")
                    val first = ++nextSequence
                    engine.mutate(key, mutations.append, "+$keyIndex-b")
                    val second = ++nextSequence
                    expectedPushes[key.identity()] = listOf(first, second)
                }

                SchedulingVerdict.PARK -> {
                    val parked = engine.mutate(key, mutations.park, "park")
                    ++nextSequence
                    parkedIds += parked
                    engine.mutate(key, mutations.append, "+$keyIndex-after-park")
                    val suffix = ++nextSequence
                    expectedPushes[key.identity()] = listOf(suffix)
                }

                SchedulingVerdict.DECLINE -> {
                    val head = engine.mutate(key, mutations.decline, "decline")
                    ++nextSequence
                    val suffix = engine.mutate(key, mutations.append, "+$keyIndex-blocked")
                    ++nextSequence
                    declinedIds[key.identity()] = listOf(head, suffix)
                    expectedPushes[key.identity()] = emptyList()
                }
            }
        }

        engine.drain()

        val actualByKey =
            backend.receivedPushes
                .groupBy { request -> request.key.identity() }
                .mapValues { (_, requests) -> requests.map { request -> request.clientSequence } }
        val normalizedActual = keys.associate { key -> key.identity() to actualByKey[key.identity()].orEmpty() }
        val context = "masterSeed=$PER_KEY_MASTER_SEED caseSeed=$caseSeed caseIndex=$caseIndex"
        assertEquals(expectedPushes, normalizedActual, context)
        assertEquals(
            parkedIds,
            engine.deadLetters().map { deadLetter -> deadLetter.mutationId }.toSet(),
            context,
        )
        declinedIds.forEach { (identity, mutationIds) ->
            val key = keys.single { candidate -> candidate.identity() == identity }
            assertEquals(mutationIds, engine.pending(key).map { pending -> pending.mutationId }, context)
        }
        expectedPushes.values.flatten().forEach { sequence ->
            assertEquals(StoredPhase.RETIRED, storage.phaseForSequence(sequence), context)
        }
    }
}

private suspend fun assertGeneratedVerdictOrderingCases() {
    val seeds = SchedulingRng(VERDICT_MASTER_SEED)
    repeat(GENERATED_SCHEDULING_CASES) { caseIndex ->
        val caseSeed = seeds.nextLong()
        val random = SchedulingRng(caseSeed)
        val storage = InMemoryMutationJournalStorage()
        val mutations = SchedulingMutations()
        val backend = FakeBackend()
        val handle = SchedulingHandle()
        val engine =
            openSchedulingEngine(
                storage,
                mutations,
                backend,
                handle,
                TestWallClock(10_000L + caseIndex),
            )
        val namespace = StoreNamespace("verdict-$caseIndex")
        val keys = (0 until 4).map { keyIndex -> MutationsTestKey("key-$keyIndex", namespace) }
        val expectedByKey = linkedMapOf<KeyIdentity, List<Long>>()
        var nextSequence = 0L

        keys.forEachIndexed { keyIndex, key ->
            when (SchedulingVerdict.entries[random.nextInt(3)]) {
                SchedulingVerdict.EXECUTE -> {
                    engine.mutate(key, mutations.append, "+$keyIndex-first")
                    val first = ++nextSequence
                    engine.mutate(key, mutations.append, "+$keyIndex-second")
                    val second = ++nextSequence
                    expectedByKey[key.identity()] = listOf(first, second)
                }

                SchedulingVerdict.PARK -> {
                    engine.mutate(key, mutations.park, "park")
                    ++nextSequence
                    engine.mutate(key, mutations.append, "+$keyIndex-after")
                    val suffix = ++nextSequence
                    expectedByKey[key.identity()] = listOf(suffix)
                }

                SchedulingVerdict.DECLINE -> {
                    engine.mutate(key, mutations.decline, "decline")
                    ++nextSequence
                    engine.mutate(key, mutations.append, "+$keyIndex-blocked")
                    ++nextSequence
                    expectedByKey[key.identity()] = emptyList()
                }
            }
        }

        engine.drain()

        val actualByKey =
            backend.receivedPushes
                .groupBy { request -> request.key.identity() }
                .mapValues { (_, requests) -> requests.map { request -> request.clientSequence } }
        val context = "masterSeed=$VERDICT_MASTER_SEED caseSeed=$caseSeed caseIndex=$caseIndex"
        keys.forEach { key ->
            val actual = actualByKey[key.identity()].orEmpty()
            assertEquals(expectedByKey.getValue(key.identity()), actual, context)
            assertEquals(actual.sorted(), actual, context)
        }
    }
}

private suspend fun TestScope.assertSameNamespaceNonownerStartsNoTransportWhileDistinctNamespaceOverlaps() {
    val storage = InMemoryMutationJournalStorage()
    val mutations = SchedulingMutations()
    val backend = retainingSchedulingBackend()
    val handle = SchedulingHandle()
    val clock = TestWallClock(20_000L)
    val namespace = StoreNamespace("authority")
    val owner = MutationsTestKey("owner", namespace)
    val nonowner = MutationsTestKey("nonowner", namespace)
    val other = MutationsTestKey("other", StoreNamespace("authority-other"))
    val ownerEntered = CompletableDeferred<Unit>()
    val releaseOwner = CompletableDeferred<Unit>()
    backend.pushBehavior = { key, value ->
        if (key.identity() == owner.identity()) {
            ownerEntered.complete(Unit)
            releaseOwner.await()
        }
        MutationPresentAck(value, "authority-${backend.receivedPushes.size}", null)
    }
    val engine = openSchedulingEngine(storage, mutations, backend, handle, clock)
    val ownerId = engine.mutate(owner, mutations.append, "+owner")
    val nonownerId = engine.mutate(nonowner, mutations.append, "+nonowner")
    val otherId = engine.mutate(other, mutations.append, "+other")
    val ownerDrain = backgroundScope.async { engine.drain(owner) }
    ownerEntered.await()

    try {
        engine.drain(nonowner)
        val otherDrain = backgroundScope.async { engine.drain(other) }
        otherDrain.await()

        assertEquals(
            listOf(owner.identity(), other.identity()),
            backend.receivedPushes.map { request -> request.key.identity() },
        )
        assertEquals(StoredPhase.INFLIGHT, storage.phaseForMutation(ownerId))
        assertEquals(StoredPhase.UNPREPARED, storage.phaseForMutation(nonownerId))
        assertEquals(StoredPhase.RETIRED, storage.phaseForMutation(otherId))
    } finally {
        releaseOwner.complete(Unit)
        ownerDrain.await()
    }

    engine.drain(nonowner)
    assertEquals(StoredPhase.RETIRED, storage.phaseForMutation(nonownerId))
}

private suspend fun assertGlobalDrainSkipsBackoffIneligibleEntry() {
    val storage = InMemoryMutationJournalStorage()
    val mutations = SchedulingMutations()
    val backend = retainingSchedulingBackend()
    val handle = SchedulingHandle()
    val clock = TestWallClock(25_000L)
    val delayed = MutationsTestKey("backoff-delayed", StoreNamespace("backoff-delayed"))
    val other = MutationsTestKey("backoff-other", StoreNamespace("backoff-other"))
    var delayedAttempts = 0
    backend.pushBehavior = { key, value ->
        if (key.identity() == delayed.identity() && delayedAttempts++ == 0) {
            throw IllegalStateException("enter deterministic backoff")
        }
        MutationPresentAck(value, "backoff-${backend.receivedPushes.size}", null)
    }
    val engine = openSchedulingEngine(storage, mutations, backend, handle, clock)
    val delayedId = engine.mutate(delayed, mutations.append, "+delayed")
    val otherId = engine.mutate(other, mutations.append, "+other")

    engine.drain(delayed)
    assertEquals(StoredPhase.READY, storage.phaseForMutation(delayedId))
    engine.drain()

    assertEquals(
        listOf(delayed.identity(), other.identity()),
        backend.receivedPushes.map { request -> request.key.identity() },
    )
    assertEquals(StoredPhase.READY, storage.phaseForMutation(delayedId))
    assertEquals(StoredPhase.RETIRED, storage.phaseForMutation(otherId))

    clock.setEpochMillis(35_000L)
    engine.drain()
    assertEquals(StoredPhase.RETIRED, storage.phaseForMutation(delayedId))
}

private suspend fun assertGlobalDrainResumesPostAttemptOwnerBeforeEnumeratedNonowner() {
    val storage = InMemoryMutationJournalStorage()
    val mutations = SchedulingMutations()
    val firstBackend = FakeBackend()
    val handle = SchedulingHandle()
    val clock = TestWallClock(30_000L)
    val namespace = StoreNamespace("global-authority")
    val nonowner = MutationsTestKey("enumerated-first", namespace)
    val owner = MutationsTestKey("post-attempt-owner", namespace)
    val other = MutationsTestKey("eligible-other", StoreNamespace("global-authority-other"))
    firstBackend.pushBehavior = { key, value ->
        if (key.identity() == owner.identity()) {
            throw IllegalStateException("retain post-attempt authority")
        }
        MutationPresentAck(value, "seed", null)
    }
    val first = openSchedulingEngine(storage, mutations, firstBackend, handle, clock)
    val nonownerId = first.mutate(nonowner, mutations.append, "+nonowner")
    val ownerId = first.mutate(owner, mutations.append, "+owner")
    val otherId = first.mutate(other, mutations.append, "+other")
    first.drain(owner)
    assertEquals(StoredPhase.READY, storage.phaseForMutation(ownerId))

    clock.setEpochMillis(40_000L)
    val replayBackend = retainingSchedulingBackend()
    val reopened = openSchedulingEngine(storage, mutations, replayBackend, handle, clock)
    reopened.drain()

    assertEquals(
        listOf(owner.identity(), other.identity()),
        replayBackend.receivedPushes.map { request -> request.key.identity() },
    )
    assertEquals(StoredPhase.UNPREPARED, storage.phaseForMutation(nonownerId))
    assertEquals(StoredPhase.RETIRED, storage.phaseForMutation(ownerId))
    assertEquals(StoredPhase.RETIRED, storage.phaseForMutation(otherId))

    reopened.drain()
    assertEquals(StoredPhase.RETIRED, storage.phaseForMutation(nonownerId))
}

private class SchedulingMutations {
    lateinit var append: MutatorRef<MutationsTestKey, String, String>
    lateinit var park: MutatorRef<MutationsTestKey, String, String>
    lateinit var decline: MutatorRef<MutationsTestKey, String, String>

    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            append =
                mutator(
                    id = "scheduling-append",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { base, suffix ->
                    MutationPresence.Present(
                        (base as? MutationPresence.Present)?.value.orEmpty() + suffix,
                    )
                }
            park =
                mutator(
                    id = "scheduling-park",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { _, _ ->
                    throw IllegalStateException("scripted property park")
                }
            decline =
                mutator(
                    id = "scheduling-decline",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { _, _ -> null }
        }
}

private class SchedulingHandle : StoreWriteHandle<MutationsTestKey, String> {
    private val values = mutableMapOf<KeyIdentity, String>()

    fun read(key: MutationsTestKey): String = values[key.identity()] ?: "base"

    override suspend fun apply(
        key: MutationsTestKey,
        value: String,
    ) {
        values[key.identity()] = value
    }

    override suspend fun markStale(key: MutationsTestKey) = Unit

    override suspend fun confirmFresh(
        key: MutationsTestKey,
        etag: String?,
    ) = Unit
}

private fun retainingSchedulingBackend(): FakeBackend =
    FakeBackend().apply {
        retireBehavior = {
            MutationRetirementAck(confirmedThroughSequence = 0L)
        }
    }

private fun openSchedulingEngine(
    storage: MutationJournalStorage,
    mutations: SchedulingMutations,
    backend: FakeBackend,
    handle: SchedulingHandle,
    clock: TestWallClock,
): MutationEngine<MutationsTestKey, String> {
    val journal =
        StorageBackedMutationJournal<String>(
            storage = storage,
            registrations = mutations.registry.registrations,
            clientId = SCHEDULING_CLIENT_ID,
            hydrateOnFirstUse = true,
        )
    return MutationEngine(
        registry = mutations.registry,
        server = backend,
        journal = journal,
        keyResolver = SCHEDULING_KEY_RESOLVER,
        valueCodecVersion = 1,
        valueCodec = FixtureStringArgsCodec,
        baseReader = { key -> handle.read(key) },
        wallClock = clock,
        backoffRandom = Random(0),
        clientId = SCHEDULING_CLIENT_ID,
    ).also { engine -> engine.bind(handle) }
}

private suspend fun MutationJournalStorage.phaseForMutation(mutationId: String): StoredPhase =
    transaction { transaction ->
        val sequence =
            transaction.intents(SCHEDULING_CLIENT_ID).single { intent ->
                intent.mutationId == mutationId
            }.clientSequence
        transaction.executions(SCHEDULING_CLIENT_ID).single { execution ->
            execution.clientSequence == sequence
        }.phase
    }

private suspend fun MutationJournalStorage.phaseForSequence(sequence: Long): StoredPhase =
    transaction { transaction ->
        transaction.executions(SCHEDULING_CLIENT_ID).single { execution ->
            execution.clientSequence == sequence
        }.phase
    }

private enum class SchedulingVerdict {
    EXECUTE,
    PARK,
    DECLINE,
}

private class SchedulingRng(seed: Long) {
    private var state = seed

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        state = state * 6364136223846793005L + 1442695040888963407L
        return ((state ushr 1) % bound.toLong()).toInt()
    }

    fun nextLong(): Long {
        state = state * 6364136223846793005L + 1442695040888963407L
        return state
    }
}

private val SCHEDULING_KEY_RESOLVER =
    MutationKeyResolver<MutationsTestKey> { identity ->
        MutationsTestKey(identity.canonicalId, StoreNamespace(identity.namespace))
    }

private const val SCHEDULING_CLIENT_ID: String = "scheduling-client"
private const val GENERATED_SCHEDULING_CASES: Int = 64
private const val PER_KEY_MASTER_SEED: Long = 0x0237_0301_2026L
private const val VERDICT_MASTER_SEED: Long = 0x0237_0302_2026L

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
