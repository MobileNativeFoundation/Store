@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAckRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredExecutionPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.testing.FakeSourceOfTruth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MutationOverlayReplayPropertyTest {
    @Test
    fun overlayProjection_isDeterministicUnderReplayPermutationsOfConfirmedInterleavings() =
        runTest(timeout = 25.seconds) {
            val fixture = replayFixture()
            val cases = fixedReplayCases() + generatedReplayCases()

            cases.forEachIndexed { caseIndex, case ->
                val schedules = confirmedInterleavings(case)
                var canonical: Map<Int, String?>? = null
                schedules.forEach { schedule ->
                    val keys = case.bases.keys.associateWith { keyId -> MutationsTestKey("property-$keyId") }
                    val storage = InMemoryMutationJournalStorage()
                    val first = openReplayEngine(storage, fixture.registry)
                    case.intents.forEach { intent ->
                        val key = keys.getValue(intent.keyId)
                        when (intent.operation) {
                            ModelOperation.APPEND -> first.mutate(key, fixture.append, intent.token)
                            ModelOperation.PREPEND -> first.mutate(key, fixture.prepend, intent.token)
                            ModelOperation.REPLACE -> first.mutate(key, fixture.replace, intent.token)
                            ModelOperation.DELETE -> first.mutate(key, fixture.delete, intent.token)
                        }
                    }
                    schedule.forEach { sequence -> retireReplaySequence(storage, sequence) }

                    val reopened = openReplayEngine(storage, fixture.registry)
                    reopened.ensureHydrated()
                    val actual =
                        keys.mapValues { (keyId, key) ->
                            reopened.overlay.apply(key, case.bases.getValue(keyId))
                        }
                    val expected = keys.keys.associateWith { keyId -> expectedProjection(case, keyId) }
                    val context =
                        "masterSeed=$MASTER_REPLAY_SEED caseSeed=${case.seed} " +
                            "caseIndex=$caseIndex schedule=$schedule case=$case"
                    assertEquals(expected, actual, context)
                    canonical?.let { firstResult -> assertEquals(firstResult, actual, context) }
                        ?: run { canonical = actual }
                }
            }
        }

    @Test
    fun identityDefaultOverlay_isNoOp_onFallbackPath() =
        runTest(timeout = 25.seconds) {
            var plainFetches = 0
            var mutationFetches = 0
            val plainSource = FakeSourceOfTruth<MutationsTestKey, String>()
            val mutationSource = FakeSourceOfTruth<MutationsTestKey, String>()
            val plain =
                store<MutationsTestKey, String> {
                    fetcher { "v${++plainFetches}" }
                    persistence(plainSource)
                }
            val server = PropertyCountingServer(authoritative = "unused")
            val mutations =
                mutationStore(
                    registry = mutatorRegistry { },
                    server = server,
                    keyResolver = MutationsTestKeyResolver,
                    valueCodecVersion = 1,
                    valueCodec = FixtureStringArgsCodec,
                ) {
                    fetcher { "v${++mutationFetches}" }
                    persistence(mutationSource)
                    journalStorage(InMemoryMutationJournalStorage())
                }
            val key = MutationsTestKey("identity-fallback")

            try {
                val plainSequence =
                    recordPropertyLifecycle(plain, key, terminal = "v2") {
                        plain.invalidate(key)
                    }
                val mutationSequence =
                    recordPropertyLifecycle(mutations, key, terminal = "v2") {
                        mutations.invalidate(key)
                    }

                assertEquals(plainSequence, mutationSequence)
                assertSame(mutationSource, mutations.sourceOfTruthRetainedByEngine)
                assertEquals(0, server.pushCount)
                assertEquals(emptyList(), mutations.pending(key))
            } finally {
                plain.close()
                mutations.close()
            }
        }

    @Test
    fun retiredIntent_neverReprojectsOldBase_onFallbackPath() =
        runTest(timeout = 25.seconds) {
            val storage = InMemoryMutationJournalStorage()
            val source = CountingPlainSourceOfTruth<MutationsTestKey, String>()
            val fixture = fallbackAppendFixture()
            val server = PropertyCountingServer(authoritative = "v2")
            val key = MutationsTestKey("retired-fallback")
            val first = openFallbackStore(storage, source, fixture.registry, server)

            try {
                first.stream(key).test {
                    awaitPropertyData { data -> data.value == "v1" && data.origin != Origin.OVERLAY }
                    first.mutate(key, fixture.append, "+op")
                    awaitPropertyData { data -> data.value == "v1+op" && data.origin == Origin.OVERLAY }
                    cancelAndIgnoreRemainingEvents()
                }

                first.drain(key)

                assertEquals("v2", source.reader(key).first { value -> value == "v2" })
                assertEquals(emptyList(), first.pending(key))
                assertEquals(1, server.pushCount)
                assertEquals(1, source.writes.count { (_, value) -> value == "v2" })
                assertEquals(
                    StoredExecutionPhase.RETIRED,
                    storage.transaction { it.executions(FALLBACK_CLIENT_ID).single().phase },
                )
            } finally {
                first.close()
            }

            val reopened = openFallbackStore(storage, source, fixture.registry, server)
            try {
                reopened.stream(key, Freshness.LocalOnly).test {
                    while (true) {
                        val item = awaitItem()
                        if (item is StoreResult.Data<String>) {
                            assertTrue(item.value != "v1" && item.value != "v1+op")
                            if (item.value == "v2") {
                                assertTrue(item.origin == Origin.SOT || item.origin == Origin.MEMORY)
                                assertTrue(item.origin != Origin.OVERLAY)
                                cancelAndIgnoreRemainingEvents()
                                break
                            }
                        }
                    }
                }
                reopened.drain(key)
                assertEquals(1, server.pushCount)
                assertEquals(1, source.writes.count { (_, value) -> value == "v2" })
            } finally {
                reopened.close()
            }
        }
}

private data class ReplayCase(
    val seed: Long,
    val bases: Map<Int, String?>,
    val intents: List<ModelIntent>,
    val confirmedPrefixByKey: Map<Int, Int>,
)

private data class ModelIntent(
    val keyId: Int,
    val clientSequence: Long,
    val operation: ModelOperation,
    val token: String,
)

private enum class ModelOperation {
    APPEND,
    PREPEND,
    REPLACE,
    DELETE,
}

private class ReplayRng(seed: Long) {
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

private data class ReplayFixture(
    val registry: MutatorRegistry<MutationsTestKey, String>,
    val append: MutatorRef<MutationsTestKey, String, String>,
    val prepend: MutatorRef<MutationsTestKey, String, String>,
    val replace: MutatorRef<MutationsTestKey, String, String>,
    val delete: MutatorRef<MutationsTestKey, String, String>,
)

private fun replayFixture(): ReplayFixture {
    lateinit var append: MutatorRef<MutationsTestKey, String, String>
    lateinit var prepend: MutatorRef<MutationsTestKey, String, String>
    lateinit var replace: MutatorRef<MutationsTestKey, String, String>
    lateinit var delete: MutatorRef<MutationsTestKey, String, String>
    val registry =
        mutatorRegistry<MutationsTestKey, String> {
            append =
                upsert("property-append", 1, FixtureStringArgsCodec, noStales()) { base, token ->
                    MutationPresence.Present(modelValue(base) + token)
                }
            prepend =
                upsert("property-prepend", 1, FixtureStringArgsCodec, noStales()) { base, token ->
                    MutationPresence.Present(token + modelValue(base))
                }
            replace =
                upsert("property-replace", 1, FixtureStringArgsCodec, noStales()) { _, token ->
                    MutationPresence.Present(token)
                }
            delete =
                upsert("property-delete", 1, FixtureStringArgsCodec, noStales()) { _, _ ->
                    MutationPresence.Absent
                }
        }
    return ReplayFixture(registry, append, prepend, replace, delete)
}

private fun modelValue(presence: MutationPresence<String>): String =
    (presence as? MutationPresence.Present)?.value ?: ABSENT_MARKER

private fun fixedReplayCases(): List<ReplayCase> =
    listOf(
        ReplayCase(
            seed = -1L,
            bases = mapOf(0 to "base"),
            intents =
                listOf(
                    ModelIntent(0, 1L, ModelOperation.APPEND, "+a"),
                    ModelIntent(0, 2L, ModelOperation.PREPEND, "b+"),
                    ModelIntent(0, 3L, ModelOperation.REPLACE, "replacement"),
                    ModelIntent(0, 4L, ModelOperation.APPEND, "+tail"),
                ),
            confirmedPrefixByKey = mapOf(0 to 1),
        ),
        ReplayCase(
            seed = -2L,
            bases = mapOf(0 to null),
            intents =
                listOf(
                    ModelIntent(0, 1L, ModelOperation.REPLACE, "created"),
                    ModelIntent(0, 2L, ModelOperation.DELETE, "ignored"),
                    ModelIntent(0, 3L, ModelOperation.APPEND, "+after"),
                ),
            confirmedPrefixByKey = mapOf(0 to 1),
        ),
        ReplayCase(
            seed = -3L,
            bases = mapOf(0 to "a", 1 to "b", 2 to null),
            intents =
                listOf(
                    ModelIntent(0, 1L, ModelOperation.APPEND, "+1"),
                    ModelIntent(1, 2L, ModelOperation.PREPEND, "2+"),
                    ModelIntent(0, 3L, ModelOperation.REPLACE, "a3"),
                    ModelIntent(2, 4L, ModelOperation.APPEND, "+4"),
                    ModelIntent(1, 5L, ModelOperation.APPEND, "+5"),
                ),
            confirmedPrefixByKey = mapOf(0 to 2, 1 to 1, 2 to 1),
        ),
    )

private fun generatedReplayCases(): List<ReplayCase> {
    val seeds = ReplayRng(MASTER_REPLAY_SEED)
    return List(GENERATED_REPLAY_CASES) {
        val caseSeed = seeds.nextLong()
        generateReplayCase(caseSeed)
    }
}

private fun generateReplayCase(seed: Long): ReplayCase {
    val random = ReplayRng(seed)
    val keyCount = random.nextInt(3) + 1
    val bases =
        (0 until keyCount).associateWith { keyId ->
            if (random.nextInt(2) == 0) null else "base-$keyId-${random.nextInt(100)}"
        }
    val intentCount = random.nextInt(6)
    val operations = ModelOperation.entries
    val intents =
        (1..intentCount).map { ordinal ->
            ModelIntent(
                keyId = random.nextInt(keyCount),
                clientSequence = ordinal.toLong(),
                operation = operations[random.nextInt(operations.size)],
                token = "t$ordinal-${random.nextInt(100)}",
            )
        }
    val confirmed =
        (0 until keyCount).associateWith { keyId ->
            val count = intents.count { intent -> intent.keyId == keyId }
            random.nextInt(count + 1)
        }
    return ReplayCase(seed, bases, intents, confirmed)
}

private fun confirmedInterleavings(case: ReplayCase): List<List<Long>> {
    val perKey =
        case.bases.keys.map { keyId ->
            case.intents
                .filter { intent -> intent.keyId == keyId }
                .take(case.confirmedPrefixByKey.getValue(keyId))
                .map { intent -> intent.clientSequence }
        }
    val positions = IntArray(perKey.size)
    val current = mutableListOf<Long>()
    val results = mutableListOf<List<Long>>()

    fun visit() {
        var advanced = false
        perKey.indices.forEach { keyIndex ->
            val position = positions[keyIndex]
            if (position < perKey[keyIndex].size) {
                advanced = true
                positions[keyIndex] += 1
                current += perKey[keyIndex][position]
                visit()
                current.removeAt(current.lastIndex)
                positions[keyIndex] -= 1
            }
        }
        if (!advanced) results += current.toList()
    }

    visit()
    return results
}

private fun expectedProjection(
    case: ReplayCase,
    keyId: Int,
): String? =
    case.intents
        .asSequence()
        .filter { intent -> intent.keyId == keyId }
        .drop(case.confirmedPrefixByKey.getValue(keyId))
        .sortedBy { intent -> intent.clientSequence }
        .fold(case.bases.getValue(keyId)) { value, intent -> applyModel(value, intent) }

private fun applyModel(
    value: String?,
    intent: ModelIntent,
): String? =
    when (intent.operation) {
        ModelOperation.APPEND -> (value ?: ABSENT_MARKER) + intent.token
        ModelOperation.PREPEND -> intent.token + (value ?: ABSENT_MARKER)
        ModelOperation.REPLACE -> intent.token
        ModelOperation.DELETE -> null
    }

private fun openReplayEngine(
    storage: MutationJournalStorage,
    registry: MutatorRegistry<MutationsTestKey, String>,
): MutationEngine<MutationsTestKey, String> =
    MutationEngine(
        registry = registry,
        server = erroringReplayServer(),
        journal =
            StorageBackedMutationJournal(
                storage = storage,
                registrations = registry.registrations,
                clientId = REPLAY_CLIENT_ID,
                hydrateOnFirstUse = true,
            ),
        keyResolver = MutationsTestKeyResolver,
        valueCodecVersion = 1,
        valueCodec = FixtureStringArgsCodec,
        clientId = REPLAY_CLIENT_ID,
    )

private fun erroringReplayServer(): MutationServer<MutationsTestKey, String> =
    object : MutationServer<MutationsTestKey, String> {
        override suspend fun push(
            request: MutationPush<MutationsTestKey, String>,
        ): MutationAck<MutationsTestKey, String> = error("The pure replay property never pushes.")

        override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
            error("The pure replay property never checkpoints.")
    }

private suspend fun retireReplaySequence(
    storage: MutationJournalStorage,
    sequence: Long,
) {
    storage.transaction { transaction ->
        val intent = transaction.intents(REPLAY_CLIENT_ID).single { row -> row.clientSequence == sequence }
        val execution = transaction.executions(REPLAY_CLIENT_ID).single { row -> row.clientSequence == sequence }
        require(execution.phase == StoredExecutionPhase.UNPREPARED)
        val preparedAt = 1_000L + sequence
        transaction.insertAttempt(
            MutationAttemptRecord(
                clientId = REPLAY_CLIENT_ID,
                clientSequence = sequence,
                generation = 1,
                effectiveNamespace = intent.namespace,
                effectiveCanonicalId = intent.canonicalId,
                valueCodecVersion = 1,
                basePresence = MutationPresenceState.PRESENT,
                baseBlob = "base-$sequence".encodeToByteArray(),
                minePresence = MutationPresenceState.PRESENT,
                mineBlob = "mine-$sequence".encodeToByteArray(),
                preconditionMetaPresent = false,
                preconditionWrittenAt = null,
                preconditionEtag = null,
                advertisedRetiredThroughSequence = 0L,
                generationIdempotencyKey = "property-$sequence-g1",
                preparedAt = preparedAt,
                conflictMetaPresent = null,
                conflictWrittenAt = null,
                conflictEtag = null,
                conflictReceivedAt = null,
            ),
        )
        transaction.advanceExecution(
            execution.copyForReplay(StoredExecutionPhase.READY, generation = 1),
        )
        transaction.advanceExecution(
            execution.copyForReplay(StoredExecutionPhase.INFLIGHT, generation = 1),
        )
        transaction.insertAck(
            MutationAckRecord(
                clientId = REPLAY_CLIENT_ID,
                clientSequence = sequence,
                generation = 1,
                authoritativePresence = MutationPresenceState.PRESENT,
                authoritativeBlob = "ack-$sequence".encodeToByteArray(),
                valueCodecVersion = 1,
                etag = "etag-$sequence",
                canonicalTargetNamespace = null,
                canonicalTargetId = null,
                receivedAt = preparedAt + 1L,
            ),
        )
        transaction.advanceExecution(
            execution.copyForReplay(
                StoredExecutionPhase.ACKED,
                generation = 1,
                attempt = 1,
                lastAttemptAt = preparedAt + 1L,
            ),
        )
        transaction.advanceExecution(
            execution.copyForReplay(
                StoredExecutionPhase.EFFECTS_PENDING,
                generation = 1,
                attempt = 1,
                lastAttemptAt = preparedAt + 1L,
            ),
        )
        transaction.advanceExecution(
            execution.copyForReplay(
                StoredExecutionPhase.RETIRED,
                generation = 1,
                attempt = 1,
                lastAttemptAt = preparedAt + 1L,
                retiredAt = preparedAt + 2L,
            ),
        )

        val client = requireNotNull(transaction.client(REPLAY_CLIENT_ID))
        val phases = transaction.executions(REPLAY_CLIENT_ID).associate { row -> row.clientSequence to row.phase }
        var prefix = client.retiredThroughSequence
        while (phases[prefix + 1L] == StoredExecutionPhase.RETIRED) prefix += 1L
        if (prefix > client.retiredThroughSequence) {
            transaction.advanceClient(
                MutationClientRecord(
                    recordVersion = client.recordVersion,
                    clientId = client.clientId,
                    lastAllocatedSequence = client.lastAllocatedSequence,
                    retiredThroughSequence = prefix,
                    serverConfirmedRetiredThroughSequence = client.serverConfirmedRetiredThroughSequence,
                    createdAt = client.createdAt,
                ),
            )
        }
    }
}

private fun MutationExecutionRecord.copyForReplay(
    phase: StoredExecutionPhase,
    generation: Int,
    attempt: Int = 0,
    lastAttemptAt: Long? = null,
    retiredAt: Long? = null,
): MutationExecutionRecord =
    MutationExecutionRecord(
        clientId = clientId,
        clientSequence = clientSequence,
        phase = phase,
        currentGeneration = generation,
        attempt = attempt,
        lastAttemptAt = lastAttemptAt,
        activeFailureId = null,
        retiredAt = retiredAt,
    )

private data class FallbackAppendFixture(
    val registry: MutatorRegistry<MutationsTestKey, String>,
    val append: MutatorRef<MutationsTestKey, String, String>,
)

private fun fallbackAppendFixture(): FallbackAppendFixture {
    lateinit var append: MutatorRef<MutationsTestKey, String, String>
    val registry =
        mutatorRegistry<MutationsTestKey, String> {
            append =
                upsert("fallback-append", 1, FixtureStringArgsCodec, noStales()) { base, suffix ->
                    val value = (base as? MutationPresence.Present)?.value.orEmpty()
                    MutationPresence.Present(value + suffix)
                }
        }
    return FallbackAppendFixture(registry, append)
}

private fun openFallbackStore(
    storage: InMemoryMutationJournalStorage,
    source: SourceOfTruth<MutationsTestKey, String>,
    registry: MutatorRegistry<MutationsTestKey, String>,
    server: MutationServer<MutationsTestKey, String>,
): MutationStore<MutationsTestKey, String> =
    mutationStore(
        registry = registry,
        server = server,
        keyResolver = MutationsTestKeyResolver,
        valueCodecVersion = 1,
        valueCodec = FixtureStringArgsCodec,
    ) {
        fetcher { "v1" }
        persistence(source)
        journalStorage(storage)
    }

private class PropertyCountingServer(
    private val authoritative: String,
) : MutationServer<MutationsTestKey, String> {
    var pushCount: Int = 0
        private set

    override suspend fun push(
        request: MutationPush<MutationsTestKey, String>,
    ): MutationAck<MutationsTestKey, String> {
        pushCount += 1
        return MutationPresentAck(authoritative, etag = "property-etag", canonicalKey = null)
    }

    override suspend fun retire(request: MutationRetirement): MutationRetirementAck =
        MutationRetirementAck(request.retiredThroughSequence)
}

private class CountingPlainSourceOfTruth<K : StoreKey, V : Any>(
    private val delegate: FakeSourceOfTruth<K, V> = FakeSourceOfTruth(),
) : SourceOfTruth<K, V> {
    val writes = mutableListOf<Pair<K, V>>()

    override fun reader(key: K): Flow<V?> = delegate.reader(key)

    override suspend fun write(
        key: K,
        value: V,
    ) {
        writes += key to value
        delegate.write(key, value)
    }

    override suspend fun delete(key: K) = delegate.delete(key)

    override suspend fun deleteNamespace(namespace: StoreNamespace) = delegate.deleteNamespace(namespace)

    override suspend fun deleteAll() = delegate.deleteAll()
}

private suspend fun recordPropertyLifecycle(
    store: Store<MutationsTestKey, String>,
    key: MutationsTestKey,
    terminal: String,
    script: suspend () -> Unit,
): List<String> {
    val recorded = mutableListOf<String>()
    var scriptRan = false
    store.stream(key).test {
        while (true) {
            when (val item = awaitItem()) {
                is StoreResult.Loading -> recorded += "loading"
                is StoreResult.Data<String> -> {
                    recorded += "data(${item.value},${item.origin},stale=${item.isStale})"
                    if (item.value == terminal) {
                        cancelAndIgnoreRemainingEvents()
                        break
                    }
                    if (!scriptRan) {
                        scriptRan = true
                        script()
                    }
                }
                is StoreResult.Revalidated -> recorded += "revalidated"
                is StoreResult.Error -> recorded += "error"
            }
        }
    }
    return recorded
}

private suspend fun ReceiveTurbine<StoreResult<String>>.awaitPropertyData(
    predicate: (StoreResult.Data<String>) -> Boolean,
): StoreResult.Data<String> {
    while (true) {
        val item = awaitItem()
        if (item is StoreResult.Data<String> && predicate(item)) return item
    }
}

private const val MASTER_REPLAY_SEED: Long = 0x0225_2026_0801L
private const val GENERATED_REPLAY_CASES: Int = 64
private const val REPLAY_CLIENT_ID: String = "property-client"
private const val FALLBACK_CLIENT_ID: String = "client-0"
private const val ABSENT_MARKER: String = "<absent>"
