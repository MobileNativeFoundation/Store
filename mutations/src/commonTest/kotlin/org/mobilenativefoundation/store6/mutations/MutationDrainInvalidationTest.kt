@file:OptIn(
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest as coroutineRunTest
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.StoreResults
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAliasState
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectDisposition
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationFailureRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyAliasRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The durable invalidation executor and post-ack failure-containment contract. */
class MutationDrainInvalidationTest {
    @Test
    fun keyEffect_usesWriteHandleMarkStale() = runTest {
        val errors = mutableListOf<String>()
        val source = MutationsTestKey("key-effect-source")
        val target = MutationsTestKey("key-effect-target")
        val directMutations = InvalidationMutations(staleKeys = setOf(target))
        val directStorage = InMemoryMutationJournalStorage()
        val directHandle = RecordingInvalidationHandle()
        val direct =
            openInvalidationEngine(
                storage = directStorage,
                mutations = directMutations,
                backend = retainingInvalidationBackend(),
                handle = directHandle,
            )
        val directId = direct.mutate(source, directMutations.set, "accepted")

        direct.drain(source)

        val directState = directStorage.invalidationState(directId)
        expect(errors, "typed KEY effect reaches StoreWriteHandle.markStale") {
            assertEquals(listOf(target.identity()), directHandle.markAttempts)
            assertEquals(MutationEffectDisposition.APPLIED, directState.effects.single().disposition)
            assertEquals(StoredPhase.RETIRED, directState.execution.phase)
        }

        val streamMutations = InvalidationMutations(staleKeys = setOf(target))
        val streamStorage = InMemoryMutationJournalStorage()
        val streamBackend = retainingInvalidationBackend()
        val users = openInvalidationStore(streamStorage, streamMutations, streamBackend)
        assertEquals("base", users.get(target))
        val refreshedValue = "key-effect-refreshed"
        val probe = observeRefresh(users, target, refreshedValue)
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        try {
            val initial = probe.initial.await()
            assertEquals("base", initial.value)
            assertTrue(!initial.isStale)
            assertTrue(!initial.refreshing)
            streamBackend.seed(target, refreshedValue)
            streamBackend.loadGate = { key ->
                if (key.identity() == target.identity()) {
                    refreshStarted.complete(Unit)
                    releaseRefresh.await()
                }
            }
            val streamId = users.mutate(source, streamMutations.set, "accepted")

            users.drain(source)
            refreshStarted.awaitFromDefault()
            releaseRefresh.complete(Unit)
            val refreshed = probe.refreshed.awaitFromDefault()

            val streamState = streamStorage.invalidationState(streamId)
            expect(errors, "active target stream observes the KEY invalidation refresh") {
                assertEquals(refreshedValue, refreshed.value)
                assertTrue(!refreshed.isStale)
                assertTrue(!refreshed.refreshing)
                assertEquals(StoredPhase.RETIRED, streamState.execution.phase)
            }
        } finally {
            releaseRefresh.complete(Unit)
            streamBackend.loadGate = null
            probe.collector.cancelAndJoin()
            users.close()
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun namespaceEffect_usesDelegateInvalidateNamespace() = runTest {
        val errors = mutableListOf<String>()
        val namespace = StoreNamespace("mutations")
        val source = MutationsTestKey("namespace-effect-source", namespace)
        val resident = MutationsTestKey("namespace-effect-resident", namespace)
        val mutations = InvalidationMutations(staleNamespaces = setOf(namespace))
        val storage = InMemoryMutationJournalStorage()
        val backend = retainingInvalidationBackend()
        val users = openInvalidationStore(storage, mutations, backend)
        assertEquals("base", users.get(resident))
        val refreshedValue = "namespace-effect-refreshed"
        val probe = observeRefresh(users, resident, refreshedValue)
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        try {
            val initial = probe.initial.await()
            assertEquals("base", initial.value)
            assertTrue(!initial.isStale)
            assertTrue(!initial.refreshing)
            backend.seed(resident, refreshedValue)
            backend.loadGate = { key ->
                if (key.identity() == resident.identity()) {
                    refreshStarted.complete(Unit)
                    releaseRefresh.await()
                }
            }
            val mutationId = users.mutate(source, mutations.set, "accepted")

            users.drain(source)

            val state = storage.invalidationState(mutationId)
            val effectApplied =
                state.effects.single().disposition == MutationEffectDisposition.APPLIED
            expect(errors, "NAMESPACE effect becomes APPLIED") {
                assertEquals(MutationEffectDisposition.APPLIED, state.effects.single().disposition)
                assertEquals(StoredPhase.RETIRED, state.execution.phase)
            }
            if (effectApplied) {
                refreshStarted.awaitFromDefault()
                releaseRefresh.complete(Unit)
                val refreshed = probe.refreshed.awaitFromDefault()
                expect(errors, "delegated namespace invalidation refreshes an active resident stream") {
                    assertEquals(refreshedValue, refreshed.value)
                    assertTrue(!refreshed.isStale)
                    assertTrue(!refreshed.refreshing)
                }
            } else {
                errors +=
                    "delegated namespace invalidation refreshes an active resident stream: " +
                        "NAMESPACE stayed PENDING; delegate refetch and refreshed Data did not run"
            }
        } finally {
            releaseRefresh.complete(Unit)
            backend.loadGate = null
            probe.collector.cancelAndJoin()
            users.close()
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun pendingEffect_replaysBeforeRetirement() = runTest {
        val errors = mutableListOf<String>()
        val target = MutationsTestKey("restart-pending-target")
        val source = MutationsTestKey("restart-pending-source")
        val mutations = InvalidationMutations(staleKeys = setOf(target))
        val backing = InMemoryMutationJournalStorage()
        val storage = FailPointJournalStorage(backing)
        val backend = retainingInvalidationBackend()
        val firstHandle = RecordingInvalidationHandle()
        val first = openInvalidationEngine(storage, mutations, backend, firstHandle)
        val mutationId = first.mutate(source, mutations.set, "accepted")
        storage.armFailTransaction(JournalFailPointBoundary.EFFECT_MARKER)

        val stagedFailure = captureFailure { first.drain(source) }
        val pending = backing.invalidationState(mutationId)
        expect(errors, "marker failure leaves durable effect work replayable") {
            assertIs<FailPointTransactionException>(assertNotNull(stagedFailure))
            assertEquals(listOf(target.identity()), firstHandle.markAttempts)
            assertEquals(StoredPhase.EFFECTS_PENDING, pending.execution.phase)
            assertEquals(MutationEffectDisposition.PENDING, pending.effects.single().disposition)
            assertEquals(0L, assertNotNull(pending.client).retiredThroughSequence)
        }

        val replayHandle = RecordingInvalidationHandle()
        val reopened = openInvalidationEngine(storage, mutations, backend, replayHandle)
        reopened.drain(source)

        val completed = backing.invalidationState(mutationId)
        expect(errors, "restart executes PENDING before retirement") {
            assertEquals(listOf(target.identity()), replayHandle.markAttempts)
            assertEquals(MutationEffectDisposition.APPLIED, completed.effects.single().disposition)
            assertEquals(StoredPhase.RETIRED, completed.execution.phase)
            assertEquals(1L, assertNotNull(completed.client).retiredThroughSequence)
            assertEquals(1, backend.receivedPushes.size)
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun pendingEffect_blocksRetirementUntilTerminal() = runTest {
        val errors = mutableListOf<String>()
        val target = MutationsTestKey("blocking-target")
        val source = MutationsTestKey("blocking-source")
        val mutations = InvalidationMutations(staleKeys = setOf(target))
        val storage = InMemoryMutationJournalStorage()
        val handle = RecordingInvalidationHandle(remainingTargetFailures = 2)
        val engine =
            openInvalidationEngine(
                storage,
                mutations,
                retainingInvalidationBackend(),
                handle,
            )
        val mutationId = engine.mutate(source, mutations.set, "accepted")

        repeat(2) { pass ->
            val exposed = captureFailure { engine.drain(source) }
            val held = storage.invalidationState(mutationId)
            expect(errors, "failed target pass ${pass + 1} is surfaced") {
                val wrapper = assertIs<StoreException>(assertNotNull(exposed))
                assertIs<StoreError.Persistence>(wrapper.error)
            }
            expect(errors, "failed target pass ${pass + 1} holds EFFECTS_PENDING") {
                assertEquals(StoredPhase.EFFECTS_PENDING, held.execution.phase)
                assertEquals(MutationEffectDisposition.PENDING, held.effects.single().disposition)
                assertEquals(0L, assertNotNull(held.client).retiredThroughSequence)
            }
        }

        engine.drain(source)

        val completed = storage.invalidationState(mutationId)
        expect(errors, "retirement follows terminal effect success") {
            assertEquals(3, handle.markAttempts.size)
            assertEquals(MutationEffectDisposition.APPLIED, completed.effects.single().disposition)
            assertEquals(StoredPhase.RETIRED, completed.execution.phase)
            assertEquals(1L, assertNotNull(completed.client).retiredThroughSequence)
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun currentAckPendingAlias_resolvesKeyEffectBeforeFacadeActivation() = runTest {
        val errors = mutableListOf<String>()
        val source = MutationsTestKey("pending-alias-source")
        val target = MutationsTestKey("pending-alias-target")
        val mutations = InvalidationMutations(staleKeys = setOf(source))
        val storage = InMemoryMutationJournalStorage()
        val backend = retainingInvalidationBackend()
        backend.pushBehavior = { _, value ->
            MutationPresentAck(value, "pending-alias-etag", target)
        }
        val targetEntered = CompletableDeferred<Unit>()
        val releaseTarget = CompletableDeferred<Unit>()
        val handle =
            RecordingInvalidationHandle(
                beforeMark = {
                    targetEntered.complete(Unit)
                    releaseTarget.await()
                },
            )
        val engine = openInvalidationEngine(storage, mutations, backend, handle)
        val mutationId = engine.mutate(source, mutations.set, "accepted")
        val drain =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                engine.drain(source)
            }

        targetEntered.await()
        val held = storage.invalidationState(mutationId)
        expect(errors, "effect work sees the current acknowledgement's pending alias") {
            assertEquals(listOf(target.identity()), handle.markAttempts)
        }
        expect(errors, "facade routing remains provisional before finalization") {
            assertEquals(StoredPhase.EFFECTS_PENDING, held.execution.phase)
            assertEquals(MutationAliasState.PENDING, held.aliases.single().state)
            assertEquals(source.identity(), engine.terminalIdentityOf(source.identity()))
        }

        releaseTarget.complete(Unit)
        drain.await()
        val completed = storage.invalidationState(mutationId)
        expect(errors, "finalization activates the alias only after the effect marker") {
            assertEquals(MutationEffectDisposition.APPLIED, completed.effects.single().disposition)
            assertEquals(MutationAliasState.ACTIVE, completed.aliases.single().state)
            assertEquals(StoredPhase.RETIRED, completed.execution.phase)
            assertEquals(target.identity(), engine.terminalIdentityOf(source.identity()))
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun serverWins_skipsEveryPendingEffect_withoutInvokingInvalidation_andAdvancesRetiredPrefix() =
        runTest {
            val errors = mutableListOf<String>()
            val source = MutationsTestKey("server-wins-source")
            val target = MutationsTestKey("server-wins-target")
            val mutations =
                InvalidationMutations(
                    staleKeys = setOf(target),
                    staleNamespaces = setOf(source.namespace),
                )
            val storage = InMemoryMutationJournalStorage()
            val backend = retainingInvalidationBackend()
            backend.pushBehavior = { _, _ -> throw conflictFailure() }
            val handle = RecordingInvalidationHandle()
            val engine =
                openInvalidationEngine(
                    storage = storage,
                    mutations = mutations,
                    backend = backend,
                    handle = handle,
                    conflicts =
                        MutationConflictRegistration(
                            precondition = null,
                            merge = { _, _, _ -> MutationConflictResolution.ServerWins },
                        ),
                )
            val mutationId = engine.mutate(source, mutations.set, "mine")

            engine.drain(source)
            val conflicted = storage.invalidationState(mutationId)
            expect(errors, "first conflict pass preserves every pending effect") {
                assertEquals(StoredPhase.REFRESH_REQUIRED, conflicted.execution.phase)
                assertEquals(2, conflicted.effects.size)
                assertTrue(
                    conflicted.effects.all {
                        it.disposition == MutationEffectDisposition.PENDING
                    },
                )
            }

            engine.drain(source)

            val retired = storage.invalidationState(mutationId)
            expect(errors, "server-wins skips all effects and advances the local prefix") {
                assertEquals(StoredPhase.RETIRED, retired.execution.phase)
                assertEquals(2, retired.effects.size)
                assertTrue(
                    retired.effects.all {
                        it.disposition == MutationEffectDisposition.SKIPPED
                    },
                )
                assertEquals(1L, assertNotNull(retired.client).retiredThroughSequence)
            }
            expect(errors, "server-wins invokes no invalidation target") {
                assertTrue(handle.markAttempts.isEmpty())
                assertEquals(1, backend.receivedPushes.size)
            }
            assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
        }

    @Test
    fun effectResolverFailure_staysPendingAndDoesNotPark() = runTest {
        val errors = mutableListOf<String>()
        val source = MutationsTestKey("resolver-failure-source")
        val target = MutationsTestKey("resolver-failure-target")
        val mutations = InvalidationMutations(staleKeys = setOf(target))
        val storage = InMemoryMutationJournalStorage()
        val resolverFailure = IllegalStateException("effect target cannot be resolved")
        val resolver =
            MutationKeyResolver<MutationsTestKey> { identity ->
                if (identity.canonicalId == target.canonicalId()) {
                    throw resolverFailure
                }
                MutationsTestKeyResolver.resolve(identity)
            }
        val engine =
            openInvalidationEngine(
                storage = storage,
                mutations = mutations,
                backend = FakeBackend(),
                handle = RecordingInvalidationHandle(),
                resolver = resolver,
            )
        val mutationId = engine.mutate(source, mutations.set, "accepted")

        val exposed = captureFailure { engine.drain(source) }
        val held = storage.invalidationState(mutationId)
        expect(errors, "resolver failure is surfaced from keyed drain") {
            val wrapper = assertIs<StoreException>(assertNotNull(exposed))
            val structured = assertIs<StoreError.Conversion>(wrapper.error)
            assertSame(resolverFailure, wrapper.cause)
            assertSame(resolverFailure, structured.cause)
        }
        expect(errors, "resolver failure records IDENTITY evidence and stays pending") {
            assertEquals(StoredPhase.EFFECTS_PENDING, held.execution.phase)
            assertEquals(MutationEffectDisposition.PENDING, held.effects.single().disposition)
            assertTrue(held.failures.any { it.kind == MutationFailureKind.IDENTITY })
        }
        expect(errors, "post-ack resolver failure never parks") {
            assertTrue(engine.deadLetters().isEmpty())
            assertEquals(null, held.execution.activeFailureId)
            assertEquals(0L, assertNotNull(held.client).retiredThroughSequence)
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun effectTargetFailure_staysPendingAndRetriesAtLeastOnce() = runTest {
        val errors = mutableListOf<String>()
        val source = MutationsTestKey("target-failure-source")
        val target = MutationsTestKey("target-failure-target")
        val mutations = InvalidationMutations(staleKeys = setOf(target))
        val storage = InMemoryMutationJournalStorage()
        val targetFailure = IllegalStateException("injected invalidation target failure")
        val handle = RecordingInvalidationHandle(nextTargetFailure = targetFailure)
        val engine =
            openInvalidationEngine(
                storage,
                mutations,
                retainingInvalidationBackend(),
                handle,
            )
        val mutationId = engine.mutate(source, mutations.set, "accepted")

        val exposed = captureFailure { engine.drain(source) }
        val held = storage.invalidationState(mutationId)
        expect(errors, "target failure is surfaced and retains replayable work") {
            val wrapper = assertIs<StoreException>(assertNotNull(exposed))
            val structured = assertIs<StoreError.Persistence>(wrapper.error)
            assertSame(targetFailure, wrapper.cause)
            assertSame(targetFailure, structured.cause)
            assertEquals(StoredPhase.EFFECTS_PENDING, held.execution.phase)
            assertEquals(MutationEffectDisposition.PENDING, held.effects.single().disposition)
        }
        expect(errors, "target failure records EFFECT evidence without parking") {
            val durableFailure = held.failures.single { it.kind == MutationFailureKind.EFFECT }
            assertEquals(targetFailure.message, durableFailure.message)
            assertTrue(engine.deadLetters().isEmpty())
        }

        engine.drain(source)

        val completed = storage.invalidationState(mutationId)
        expect(errors, "later pass re-executes the target and reaches terminal state") {
            assertEquals(listOf(target.identity(), target.identity()), handle.markAttempts)
            assertEquals(MutationEffectDisposition.APPLIED, completed.effects.single().disposition)
            assertEquals(StoredPhase.RETIRED, completed.execution.phase)
        }
        assertTrue(errors.isEmpty(), errors.joinToString(separator = "\n"))
    }

    @Test
    fun effectCancellation_rethrowsAndLeavesPending() = runTest {
        val source = MutationsTestKey("effect-cancellation-source")
        val target = MutationsTestKey("effect-cancellation-target")
        val mutations = InvalidationMutations(staleKeys = setOf(target))
        val storage = InMemoryMutationJournalStorage()
        val cancellation = CancellationException("cancel effect target")
        val handle = RecordingInvalidationHandle(nextTargetFailure = cancellation)
        val engine = openInvalidationEngine(storage, mutations, FakeBackend(), handle)
        val mutationId = engine.mutate(source, mutations.set, "accepted")

        val thrown = assertFailsWith<CancellationException> { engine.drain(source) }

        val held = storage.invalidationState(mutationId)
        assertSame(cancellation, thrown)
        assertEquals(StoredPhase.EFFECTS_PENDING, held.execution.phase)
        assertEquals(MutationEffectDisposition.PENDING, held.effects.single().disposition)
        assertTrue(held.failures.isEmpty())
        assertTrue(engine.deadLetters().isEmpty())
        assertEquals(0L, assertNotNull(held.client).retiredThroughSequence)
    }
}

private class InvalidationMutations(
    staleKeys: Set<MutationsTestKey> = emptySet(),
    staleNamespaces: Set<StoreNamespace> = emptySet(),
) {
    lateinit var set: MutatorRef<MutationsTestKey, String, String>

    val registry: MutatorRegistry<MutationsTestKey, String> =
        mutatorRegistry {
            set =
                upsert(
                    id = "invalidation-set",
                    version = 1,
                    codec = FixtureStringArgsCodec,
                    stales =
                        typedStales(
                            keys = staleKeys,
                            namespaces = staleNamespaces,
                        ),
                ) { _, value -> MutationPresence.Present(value) }
        }
}

private class RecordingInvalidationHandle(
    var remainingTargetFailures: Int = 0,
    var nextTargetFailure: Throwable? = null,
    private val beforeMark: suspend (MutationsTestKey) -> Unit = {},
) : StoreWriteHandle<MutationsTestKey, String> {
    val markAttempts = mutableListOf<KeyIdentity>()

    override suspend fun apply(
        key: MutationsTestKey,
        value: String,
    ) = Unit

    override suspend fun markStale(key: MutationsTestKey) {
        markAttempts += key.identity()
        beforeMark(key)
        nextTargetFailure?.let { failure ->
            nextTargetFailure = null
            throw failure
        }
        if (remainingTargetFailures > 0) {
            remainingTargetFailures -= 1
            throw IllegalStateException("injected invalidation target failure")
        }
    }

    override suspend fun confirmFresh(
        key: MutationsTestKey,
        etag: String?,
    ) = Unit
}

private fun retainingInvalidationBackend(): FakeBackend =
    FakeBackend().apply {
        retireBehavior = {
            MutationRetirementAck(confirmedThroughSequence = 0L)
        }
    }

private fun openInvalidationEngine(
    storage: MutationJournalStorage,
    mutations: InvalidationMutations,
    backend: FakeBackend,
    handle: RecordingInvalidationHandle,
    resolver: MutationKeyResolver<MutationsTestKey> = MutationsTestKeyResolver,
    conflicts: MutationConflictRegistration<MutationsTestKey, String>? = null,
): MutationEngine<MutationsTestKey, String> {
    val journal =
        StorageBackedMutationJournal<String>(
            storage = storage,
            registrations = mutations.registry.registrations,
            clientId = INVALIDATION_CLIENT_ID,
            hydrateOnFirstUse = true,
        )
    return MutationEngine(
        registry = mutations.registry,
        server = backend,
        journal = journal,
        keyResolver = resolver,
        valueCodecVersion = 1,
        valueCodec = FixtureStringArgsCodec,
        conflicts = conflicts,
        baseReader = { "base" },
        clientId = INVALIDATION_CLIENT_ID,
    ).also { it.bind(handle) }
}

private fun openInvalidationStore(
    storage: MutationJournalStorage,
    mutations: InvalidationMutations,
    backend: FakeBackend,
): MutationStore<MutationsTestKey, String> =
    mutationStore(
        registry = mutations.registry,
        server = backend,
        keyResolver = MutationsTestKeyResolver,
        valueCodecVersion = 1,
        valueCodec = FixtureStringArgsCodec,
    ) {
        fetcherOfResult { backend.loadResult(it) }
        journalStorage(storage)
    }

private data class RefreshProbe(
    val initial: CompletableDeferred<StoreResult.Data<String>>,
    val refreshed: CompletableDeferred<StoreResult.Data<String>>,
    val collector: Job,
)

private fun TestScope.observeRefresh(
    store: MutationStore<MutationsTestKey, String>,
    key: MutationsTestKey,
    refreshedValue: String,
): RefreshProbe {
    val initial = CompletableDeferred<StoreResult.Data<String>>()
    val refreshed = CompletableDeferred<StoreResult.Data<String>>()
    val collector =
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.stream(key).collect { result ->
                if (result is StoreResult.Data) {
                    initial.complete(result)
                    if (result.value == refreshedValue && !result.isStale) {
                        refreshed.complete(result)
                    }
                }
            }
        }
    return RefreshProbe(initial, refreshed, collector)
}

// Preserve Default-dispatch ordering and let the suite-level runTest bound own cancellation.
private suspend fun <T> CompletableDeferred<T>.awaitFromDefault(): T =
    withContext(Dispatchers.Default) {
        await()
    }

private data class InvalidationState(
    val client: MutationClientRecord?,
    val execution: MutationExecutionRecord,
    val effects: List<MutationEffectRecord>,
    val failures: List<MutationFailureRecord>,
    val aliases: List<MutationKeyAliasRecord>,
)

private suspend fun MutationJournalStorage.invalidationState(
    mutationId: String,
): InvalidationState =
    transaction { transaction ->
        val intent =
            transaction.intents(INVALIDATION_CLIENT_ID).single {
                it.mutationId == mutationId
            }
        val sequence = intent.clientSequence
        InvalidationState(
            client = transaction.client(INVALIDATION_CLIENT_ID),
            execution =
                transaction.executions(INVALIDATION_CLIENT_ID).single {
                    it.clientSequence == sequence
                },
            effects =
                transaction.effects(INVALIDATION_CLIENT_ID).filter {
                    it.clientSequence == sequence
                },
            failures =
                transaction.failures(INVALIDATION_CLIENT_ID).filter {
                    it.clientSequence == sequence
                },
            aliases =
                transaction.aliases().filter {
                    it.createdByClientId == INVALIDATION_CLIENT_ID &&
                        it.createdBySequence == sequence
                },
        )
    }

private fun conflictFailure(): StoreException =
    StoreResults.exception(
        StoreResults.conflict(serverMeta = null, message = "invalidation server conflict"),
        IllegalStateException("backend conflict cause"),
    )

private suspend fun captureFailure(block: suspend () -> Unit): Throwable? =
    try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }

private suspend fun expect(
    errors: MutableList<String>,
    label: String,
    assertion: suspend () -> Unit,
) {
    try {
        assertion()
    } catch (failure: Throwable) {
        errors += "$label: ${failure.message}"
    }
}

private const val INVALIDATION_CLIENT_ID = "client-0"
private val INVALIDATION_TEST_TIMEOUT = 25.seconds

private fun runTest(testBody: suspend TestScope.() -> Unit): TestResult =
    coroutineRunTest(timeout = INVALIDATION_TEST_TIMEOUT) { testBody() }
