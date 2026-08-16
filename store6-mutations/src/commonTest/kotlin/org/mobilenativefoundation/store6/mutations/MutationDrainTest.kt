@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectDisposition
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `drain(key)` and `drain()` are idempotent, scheduler-agnostic foreground passes, and the
 * resolver — not any live key map — is global drain's correctness path. Restart enumeration is
 * covered by `MutationJournalContractTest`; parked-identity continuation, retryable post-ack
 * continuation, and the one-attempt-per-phase rule by `MutationDrainParkingTest` and
 * `MutationDrainResumabilityMatrixTest`.
 */
class MutationDrainTest {
    @Test
    fun globalDrain_reconstructsKeyAfterLiveKeyCacheIsCleared() = runTest {
        val mutation = DrainRenameMutation()
        val backend = FakeBackend()
        val resolverCalls = mutableListOf<Pair<String, String>>()
        val engine =
            MutationEngine(
                registry = mutation.registry,
                server = backend,
                keyResolver =
                    MutationKeyResolver { identity ->
                        resolverCalls += identity.namespace to identity.canonicalId
                        MutationsTestKeyResolver.resolve(identity)
                    },
                baseReader = { "base" },
            )
        engine.bind(DrainNoopHandle)
        val key = MutationsTestKey("reconstructed")
        engine.mutate(key, mutation.ref, "recovered-value")

        // The live key map is cache only: with every cached resolution dropped, the durable
        // journal identity plus the resolver must reconstruct K or the drain cannot proceed.
        engine.clearLiveKeyCache()
        engine.drain()

        assertEquals(listOf("mutations" to "reconstructed"), resolverCalls)
        assertEquals(listOf("recovered-value"), backend.pushedValues)
        assertEquals("reconstructed", backend.receivedPushes.single().identity.canonicalId)
        assertEquals(emptyList(), engine.pending(key))
        assertEquals(emptyList(), engine.drainFailuresForInspection())
    }

    @Test
    fun keyedDrain_isIdempotentForegroundPass() = runTest {
        val mutation = DrainRenameMutation()
        val backend = FakeBackend()
        val key = MutationsTestKey("keyed-idempotent")
        val untouched = MutationsTestKey("keyed-untouched")
        val users =
            mutationStore(
                registry = mutation.registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { backend.load(it) }
            }

        try {
            assertEquals("base", users.get(key))
            val fetchesBeforeDrain = backend.fetchCount
            users.mutate(key, mutation.ref, "pending")

            users.drain(key)

            assertEquals(listOf("pending"), backend.pushedValues)
            assertEquals(emptyList(), users.pending(key))

            // A second keyed pass over retired work and a pass over a key that never had work
            // are both no-ops: no push, no fetch, no invented failure (D12/D14).
            users.drain(key)
            users.drain(untouched)

            assertEquals(listOf("pending"), backend.pushedValues)
            assertEquals(fetchesBeforeDrain, backend.fetchCount)
            assertEquals(emptyList(), users.pending(key))
        } finally {
            users.close()
        }
    }

    @Test
    fun globalDrain_processesMultipleIdentitiesDeterministically() = runTest {
        val mutation = DrainAppendMutation()
        val backend = FakeBackend()
        val first = MutationsTestKey("multi-a")
        val second = MutationsTestKey("multi-b")
        val third = MutationsTestKey("multi-c")
        val users =
            mutationStore(
                registry = mutation.registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { backend.load(it) }
            }

        try {
            users.mutate(first, mutation.ref, "+a1")
            users.mutate(first, mutation.ref, "+a2")
            users.mutate(second, mutation.ref, "+b")
            users.mutate(third, mutation.ref, "+c")

            users.drain()

            // Identities are processed in first-enqueue order and each identity's intents flush
            // by durable client sequence; the second first-key push rides the first's echo.
            assertEquals(
                listOf("multi-a", "multi-a", "multi-b", "multi-c"),
                backend.receivedPushes.map { push -> push.identity.canonicalId },
            )
            assertEquals(listOf("+a1", "+a1+a2", "+b", "+c"), backend.pushedValues)
            val firstKeySequences =
                backend.receivedPushes
                    .filter { push -> push.identity.canonicalId == "multi-a" }
                    .map(MutationPush<MutationsTestKey, String>::clientSequence)
            assertEquals(firstKeySequences, firstKeySequences.sorted())
            assertEquals(emptyList(), users.pendingWrites())

            // Re-running over fully retired work changes nothing.
            users.drain()
            assertEquals(4, backend.receivedPushes.size)
        } finally {
            users.close()
        }
    }

    @Test
    fun emptyGlobalDrain_isIdempotentNoOp() = runTest {
        val mutation = DrainRenameMutation()
        val backend = FakeBackend()
        val users =
            mutationStore(
                registry = mutation.registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { backend.load(it) }
            }

        try {
            users.drain()
            users.drain()

            assertEquals(emptyList(), backend.receivedPushes)
            assertEquals(0, backend.fetchCount)
            assertEquals(emptyList(), users.pendingWrites())
            assertEquals(emptyList(), users.deadLetters())
        } finally {
            users.close()
        }
    }

    @Test
    fun oneResolverFailure_doesNotBlockAnotherIdentity() = runTest {
        val mutation = DrainRenameMutation()
        val backend = FakeBackend()
        val engine =
            MutationEngine(
                registry = mutation.registry,
                server = backend,
                keyResolver =
                    MutationKeyResolver { identity ->
                        if (identity.canonicalId == "poisoned-identity") {
                            throw IllegalStateException("cold lookup failed")
                        }
                        MutationsTestKeyResolver.resolve(identity)
                    },
                baseReader = { "base" },
            )
        engine.bind(DrainNoopHandle)
        val poisoned = MutationsTestKey("poisoned-identity")
        val healthy = MutationsTestKey("healthy-identity")
        // The failing identity enqueues first so continuation past it is what the test proves.
        val poisonedId = engine.mutate(poisoned, mutation.ref, "never-pushed")
        engine.mutate(healthy, mutation.ref, "healthy-value")
        engine.clearLiveKeyCache()

        engine.drain()

        assertEquals(listOf("healthy-value"), backend.pushedValues)
        assertEquals(emptyList(), engine.pending(healthy))
        assertEquals(
            listOf(poisonedId),
            engine.pending(poisoned).map(PendingIntent::mutationId),
        )
        val failure = engine.drainFailuresForInspection().single()
        assertEquals(MutationFailureKind.IDENTITY, failure.kind)
        assertEquals(DRAIN_FAILURE_DETAIL_RESOLVER_THROW, failure.detail)
        assertEquals("cold lookup failed", failure.message)
    }

    @Test
    fun declinedHead_leavesSuffixPendingAndGlobalDrainProcessesOtherIdentities() = runTest {
        lateinit var strictUpdate: MutatorRef<MutationsTestKey, String, String>
        lateinit var rename: MutatorRef<MutationsTestKey, String, String>
        val registry =
            mutatorRegistry<MutationsTestKey, String> {
                strictUpdate =
                    update(
                        id = "strict-update",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { _, value -> value }
                rename =
                    mutator(
                        id = "rename",
                        version = 1,
                        codec = FixtureStringArgsCodec,
                        stales = noStales(),
                    ) { _, value -> MutationPresence.Present(value) }
            }
        val backend = FakeBackend()
        val engine =
            MutationEngine(
                registry = registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                baseReader = { key -> if (key.canonicalId() == "declined-key") null else "base" },
            )
        engine.bind(DrainNoopHandle)
        val declined = MutationsTestKey("declined-key")
        val healthy = MutationsTestKey("healthy-key")
        // `update` over a stably absent base declines: the head stays PENDING and blocks
        // only its own same-effective-key suffix.
        val declinedHead = engine.mutate(declined, strictUpdate, "never-applies")
        val blockedSuffix = engine.mutate(declined, rename, "blocked-behind-decline")
        engine.mutate(healthy, rename, "healthy-value")

        engine.drain()

        assertEquals(listOf("healthy-value"), backend.pushedValues)
        assertEquals(
            listOf(declinedHead, blockedSuffix),
            engine.pending(declined).map(PendingIntent::mutationId),
        )
        assertEquals(emptyList(), engine.pending(healthy))
        // A deliberate decline is not a failure: no normalized carrier and no poison.
        assertEquals(emptyList(), engine.drainFailuresForInspection())
        assertTrue(engine.poisoned.replayCache.isEmpty())
    }

    @Test
    fun foregroundPassAttemptsEachFailedLocalPhaseOnlyOnce() = runTest {
        val backing = InMemoryMutationJournalStorage()
        val storage = FailPointJournalStorage(backing)
        val mutations = DrainLocalPhaseMutations()
        val backend = FakeBackend()
        val handle = ScriptedDrainPhaseHandle()
        val engine = openDrainPhaseEngine(storage, mutations, backend, handle)
        val adoption = drainPhaseKey("adoption")
        val effect = drainPhaseKey("effect")
        val finalization = drainPhaseKey("finalization")

        suspend fun stage(
            key: MutationsTestKey,
            ref: MutatorRef<MutationsTestKey, String, String>,
            boundary: JournalFailPointBoundary,
        ): String {
            val mutationId = engine.mutate(key, ref, "+${key.canonicalId()}")
            storage.armFailTransaction(boundary)
            assertIs<FailPointTransactionException>(captureDrainPhaseFailure { engine.drain(key) })
            return mutationId
        }

        val adoptionId = stage(adoption, mutations.plain, JournalFailPointBoundary.ADOPTION_ADVANCE)
        // Keep effect 2 PENDING after effect 1's one-shot marker failure during this setup pass.
        handle.remainingMarkStaleFailures[mutations.effectTargets(effect).last().identity()] = 1
        val effectId = stage(effect, mutations.effectful, JournalFailPointBoundary.EFFECT_MARKER)
        val finalizationId =
            stage(finalization, mutations.plain, JournalFailPointBoundary.FINALIZATION)

        handle.applyAttempts.clear()
        handle.markStaleAttempts.clear()
        handle.remainingApplyFailures[adoption.identity()] = 1
        mutations.effectTargets(effect).forEach { target ->
            handle.remainingMarkStaleFailures[target.identity()] = 1
        }
        storage.triggeredBoundaries.clear()
        storage.armFailTransaction(JournalFailPointBoundary.FINALIZATION)
        val pushCount = backend.receivedPushes.size

        val exposed = captureDrainPhaseFailure { engine.drain() }
        val (phases, effectDispositions) =
            backing.transaction { transaction ->
                val intents = transaction.intents(DRAIN_PHASE_CLIENT_ID).associateBy { it.mutationId }
                val phases =
                    transaction.executions(DRAIN_PHASE_CLIENT_ID).associate { execution ->
                        val mutationId =
                            intents.values.single { intent ->
                                intent.clientSequence == execution.clientSequence
                            }.mutationId
                        mutationId to execution.phase
                    }
                val effectSequence = intents.getValue(effectId).clientSequence
                val effectDispositions =
                    transaction
                        .effects(DRAIN_PHASE_CLIENT_ID)
                        .filter { effect -> effect.clientSequence == effectSequence }
                        .sortedBy { effect -> effect.effectIndex }
                        .map { effect -> effect.disposition }
                phases to effectDispositions
            }
        val deadLetters = engine.deadLetters()
        val errors = mutableListOf<String>()
        expectDrainPhase(errors, "global foreground pass returns normally") { assertNull(exposed) }
        expectDrainPhase(errors, "adoption is attempted once") {
            assertEquals(listOf(adoption.identity()), handle.applyAttempts)
            assertEquals(StoredPhase.ACKED, phases.getValue(adoptionId))
        }
        expectDrainPhase(errors, "each runnable effect is attempted once") {
            assertEquals(
                mutations.effectTargets(effect).map { target -> target.identity() },
                handle.markStaleAttempts,
            )
            assertEquals(
                listOf(
                    MutationEffectDisposition.PENDING,
                    MutationEffectDisposition.PENDING,
                ),
                effectDispositions,
            )
            assertEquals(StoredPhase.EFFECTS_PENDING, phases.getValue(effectId))
        }
        expectDrainPhase(errors, "finalization is attempted once") {
            assertEquals(listOf(JournalFailPointBoundary.FINALIZATION), storage.triggeredBoundaries)
            assertEquals(StoredPhase.EFFECTS_PENDING, phases.getValue(finalizationId))
        }
        expectDrainPhase(errors, "no failed local phase repushes or parks") {
            assertEquals(pushCount, backend.receivedPushes.size)
            assertTrue(deadLetters.isEmpty())
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun pushCancellation_rethrowsAndReplayUsesExactInflightGeneration() = runTest {
        val storage = InMemoryMutationJournalStorage()
        val mutation = DrainAppendMutation()
        val backend =
            FakeBackend().apply {
                retireBehavior = {
                    MutationRetirementAck(confirmedThroughSequence = 0L)
                }
            }
        val pushEntered = CompletableDeferred<Unit>()
        val releasePush = CompletableDeferred<Unit>()
        backend.pushBehavior = { _, value ->
            pushEntered.complete(Unit)
            releasePush.await()
            MutationPresentAck(value, "etag-replayed", null)
        }
        val key = MutationsTestKey("cancelled-inflight-replay")
        val first =
            mutationStore(
                registry = mutation.registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { backend.load(it) }
                journalStorage(storage)
            }

        val firstPush: MutationPush<MutationsTestKey, String>
        try {
            assertEquals("base", first.get(key))
            first.mutate(key, mutation.ref, "+pending")
            val draining =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    first.drain(key)
                }
            pushEntered.await()
            assertFalse(draining.isCompleted)
            firstPush = backend.receivedPushes.single()

            val inflight = storage.transaction { it.executions("client-0").single() }
            assertEquals(StoredPhase.INFLIGHT, inflight.phase)
            assertEquals(0, inflight.attempt)
            assertNull(inflight.lastAttemptAt)
            assertNull(inflight.activeFailureId)
            assertEquals(emptyList(), storage.transaction { it.failures("client-0") })

            draining.cancel(CancellationException("host cancelled the in-flight push"))
            assertFailsWith<CancellationException> { draining.await() }

            val afterCancellation = storage.transaction { it.executions("client-0").single() }
            assertEquals(StoredPhase.INFLIGHT, afterCancellation.phase)
            assertEquals(0, afterCancellation.attempt)
            assertNull(afterCancellation.lastAttemptAt)
            assertNull(afterCancellation.activeFailureId)
            assertEquals(emptyList(), storage.transaction { it.failures("client-0") })
        } finally {
            releasePush.complete(Unit)
            first.close()
        }

        backend.pushBehavior = { _, value -> MutationPresentAck(value, "etag-replayed", null) }
        val reopened =
            mutationStore(
                registry = mutation.registry,
                server = backend,
                keyResolver = MutationsTestKeyResolver,
                valueCodecVersion = 1,
                valueCodec = FixtureStringArgsCodec,
            ) {
                fetcher { backend.load(it) }
                journalStorage(storage)
            }
        try {
            reopened.drain(key)
            val replay = backend.receivedPushes.last()

            assertEquals(2, backend.receivedPushes.size)
            assertEquals(firstPush.clientId, replay.clientId)
            assertEquals(firstPush.clientSequence, replay.clientSequence)
            assertEquals(firstPush.mutationId, replay.mutationId)
            assertEquals(firstPush.generation, replay.generation)
            assertEquals(firstPush.identity.namespace, replay.identity.namespace)
            assertEquals(firstPush.identity.canonicalId, replay.identity.canonicalId)
            assertEquals(firstPush.retiredThroughSequence, replay.retiredThroughSequence)
            assertEquals(firstPush.idempotencyKey, replay.idempotencyKey)
            assertEquals(firstPush.valueCodecVersion, replay.valueCodecVersion)
            assertEquals(
                assertIs<MutationPresence.Present<String>>(firstPush.base).value,
                assertIs<MutationPresence.Present<String>>(replay.base).value,
            )
            assertEquals(
                assertIs<MutationPresence.Present<String>>(firstPush.mine).value,
                assertIs<MutationPresence.Present<String>>(replay.mine).value,
            )
            assertEquals(firstPush.baseMeta?.writtenAtEpochMillis, replay.baseMeta?.writtenAtEpochMillis)
            assertEquals(firstPush.baseMeta?.etag, replay.baseMeta?.etag)
            assertEquals(
                StoredPhase.RETIRED,
                storage.transaction { it.executions("client-0").single().phase },
            )
            assertEquals(emptyList(), storage.transaction { it.failures("client-0") })
        } finally {
            reopened.close()
        }
    }
}

private class DrainRenameMutation {
    lateinit var ref: MutatorRef<MutationsTestKey, String, String>
    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            ref =
                mutator(
                    id = "rename",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { _, value -> MutationPresence.Present(value) }
        }
}

private class DrainAppendMutation {
    lateinit var ref: MutatorRef<MutationsTestKey, String, String>
    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            ref =
                mutator(
                    id = "append",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { base, suffix ->
                    MutationPresence.Present(
                        ((base as? MutationPresence.Present)?.value).orEmpty() + suffix,
                    )
                }
        }
}

private class DrainLocalPhaseMutations {
    lateinit var plain: MutatorRef<MutationsTestKey, String, String>
    lateinit var effectful: MutatorRef<MutationsTestKey, String, String>

    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            plain =
                upsert(
                    id = "drain-phase-plain",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = noStales(),
                ) { base, suffix ->
                    MutationPresence.Present(
                        (base as? MutationPresence.Present)?.value.orEmpty() + suffix,
                    )
                }
            effectful =
                upsert(
                    id = "drain-phase-effectful",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales = { key, _ ->
                        StaleSet(
                            keys = effectTargets(key).toSet(),
                            namespaces = emptySet(),
                        )
                    },
                ) { base, suffix ->
                    MutationPresence.Present(
                        (base as? MutationPresence.Present)?.value.orEmpty() + suffix,
                    )
                }
        }

    fun effectTargets(key: MutationsTestKey): List<MutationsTestKey> =
        listOf(
            MutationsTestKey("${key.canonicalId()}-target-1", key.namespace),
            MutationsTestKey("${key.canonicalId()}-target-2", key.namespace),
        )
}

private class ScriptedDrainPhaseHandle : StoreWriteHandle<MutationsTestKey, String> {
    val remainingApplyFailures = mutableMapOf<KeyIdentity, Int>()
    val remainingMarkStaleFailures = mutableMapOf<KeyIdentity, Int>()
    val applyAttempts = mutableListOf<KeyIdentity>()
    val markStaleAttempts = mutableListOf<KeyIdentity>()

    override suspend fun apply(
        key: MutationsTestKey,
        value: String,
    ) {
        val identity = key.identity()
        applyAttempts += identity
        val remaining = remainingApplyFailures[identity] ?: 0
        if (remaining > 0) {
            remainingApplyFailures[identity] = remaining - 1
            throw IllegalStateException("scripted adoption failure")
        }
    }

    override suspend fun markStale(key: MutationsTestKey) {
        val identity = key.identity()
        markStaleAttempts += identity
        val remaining = remainingMarkStaleFailures[identity] ?: 0
        if (remaining > 0) {
            remainingMarkStaleFailures[identity] = remaining - 1
            throw IllegalStateException("scripted effect failure")
        }
    }

    override suspend fun confirmFresh(
        key: MutationsTestKey,
        etag: String?,
    ) = Unit
}

private fun openDrainPhaseEngine(
    storage: MutationJournalStorage,
    mutations: DrainLocalPhaseMutations,
    backend: FakeBackend,
    handle: ScriptedDrainPhaseHandle,
): MutationEngine<MutationsTestKey, String> {
    val journal =
        StorageBackedMutationJournal<String>(
            storage = storage,
            registrations = mutations.registry.registrations,
            clientId = DRAIN_PHASE_CLIENT_ID,
            hydrateOnFirstUse = true,
        )
    val resolver =
        MutationKeyResolver<MutationsTestKey> { identity ->
            MutationsTestKey(identity.canonicalId, StoreNamespace(identity.namespace))
        }
    return MutationEngine(
        registry = mutations.registry,
        server = backend,
        journal = journal,
        keyResolver = resolver,
        valueCodecVersion = 1,
        valueCodec = FixtureStringArgsCodec,
        baseReader = { "base" },
        clientId = DRAIN_PHASE_CLIENT_ID,
    ).also { engine -> engine.bind(handle) }
}

private fun drainPhaseKey(label: String): MutationsTestKey =
    MutationsTestKey(label, StoreNamespace("drain-phase-$label"))

private suspend fun captureDrainPhaseFailure(block: suspend () -> Unit): Throwable? =
    try {
        block()
        null
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        failure
    }

private fun expectDrainPhase(
    errors: MutableList<String>,
    label: String,
    assertion: () -> Unit,
) {
    try {
        assertion()
    } catch (failure: AssertionError) {
        errors += "$label: ${failure.message}"
    }
}

private object DrainNoopHandle : StoreWriteHandle<MutationsTestKey, String> {
    override suspend fun apply(
        key: MutationsTestKey,
        value: String,
    ) = Unit

    override suspend fun markStale(key: MutationsTestKey) = Unit

    override suspend fun confirmFresh(
        key: MutationsTestKey,
        etag: String?,
    ) = Unit
}

private const val DRAIN_PHASE_CLIENT_ID: String = "client-0"

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = 25.seconds, testBody = testBody)
