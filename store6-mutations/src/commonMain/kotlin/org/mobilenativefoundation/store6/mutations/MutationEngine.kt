@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.Overlay
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.StoreResults
import org.mobilenativefoundation.store6.core.seam.StoreWriteHandle
import org.mobilenativefoundation.store6.core.seam.WallClock
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAckRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectDisposition
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectKind
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectRecord as StoredEffectRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredExecutionPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationFailureRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationIntentRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyAliasRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyTombstoneRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationTombstoneState

private const val HYDRATION_FAILURE_DETAIL_VALUE_PRE_ACK: String = "value-codec-pre-ack"
private const val HYDRATION_FAILURE_DETAIL_VALUE_ACKED: String = "value-codec-acked"
private const val HYDRATION_FAILURE_DETAIL_MUTATOR_MISSING: String = "mutator-missing"
private const val HYDRATION_FAILURE_DETAIL_ARGS: String = "args-codec"

/** The single attempt generation the codec-less path transmits; it never prepares a merge. */
private const val IN_MEMORY_GENERATION: Int = 1

/** The internal default exponential-backoff constants; no public policy door exists. */
private const val BACKOFF_BASE_MILLIS: Long = 1_000L
private const val BACKOFF_CAP_MILLIS: Long = 300_000L

/** The trailing unchanged-conflict receipt bound. */
private const val CONFLICT_UNCHANGED_BOUND: Int = 3

/** Stable machine detail for a resolver that returned null during global drain. */
internal const val DRAIN_FAILURE_DETAIL_RESOLVER_NULL: String = "resolver-null"

/** Stable machine detail for a resolver whose returned pair mismatched the request. */
internal const val DRAIN_FAILURE_DETAIL_IDENTITY_MISMATCH: String = "resolver-identity-mismatch"

/** Stable machine detail for a resolver that threw a non-cancellation failure. */
internal const val DRAIN_FAILURE_DETAIL_RESOLVER_THROW: String = "resolver-throw"

/** Stable machine detail for a mutator projection that threw before transport. */
internal const val DRAIN_FAILURE_DETAIL_PROJECTION_THROW: String = "projection-throw"

/** Stable machine detail for a keyed drain whose aliased terminal key failed to resolve. */
internal const val DRAIN_FAILURE_DETAIL_KEYED_TERMINAL_UNRESOLVED: String =
    "keyed-terminal-unresolved"

/**
 * Mutations-owned production clock used when the builder's `wallClock` door is unset. Core's
 * own default (`SystemWallClock`) is internal to store6-core and cannot be threaded from this
 * module; `kotlin.time.Clock` has been stable stdlib API since Kotlin 2.3.
 */
internal val MutationsSystemWallClock: WallClock =
    object : WallClock {
        override fun nowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()
    }

/**
 * The outcome of one terminal-identity resolution attempt for a facade entry point.
 *
 * [Resolved] carries the terminal `K` and its identity. [Failed] carries the attempted terminal
 * identity plus the sanctioned conversion message/cause pair: resolver null and identity
 * mismatch have `cause == null`; a thrown non-cancellation failure retains its original cause
 * only in this immediate carrier, never in durable state.
 */
internal sealed interface TerminalKeyResolution<out K : StoreKey> {
    val identity: KeyIdentity

    class Resolved<K : StoreKey>(
        val key: K,
        override val identity: KeyIdentity,
    ) : TerminalKeyResolution<K>

    class Failed(
        override val identity: KeyIdentity,
        val detail: String,
        val message: String,
        val cause: Throwable?,
    ) : TerminalKeyResolution<Nothing>
}

internal class MutationEngine<K : StoreKey, V : Any>(
    private val registry: MutatorRegistry<K, V>,
    private val server: MutationServer<K, V>,
    private val journal: MutationJournal<V> = InMemoryMutationJournal(),
    // The exact Bookkeeper/SourceOfTruth instances installed in the delegated Store are
    // retained here; ordered base capture reads [bookkeeper].
    internal val bookkeeper: Bookkeeper = MutationBookkeeper(),
    internal val sourceOfTruth: SourceOfTruth<K, V> = MutationSourceOfTruth(),
    // Retained factory inputs. The resolver is the global-drain correctness path; the value
    // codec/version isolate every push and adoption behind defensive blob copies; conflicts is
    // stored for the conflict pipeline. Defaults exist only for direct engine construction in
    // module tests.
    internal val keyResolver: MutationKeyResolver<K> = MutationKeyResolver { null },
    internal val valueCodecVersion: Int = 1,
    internal val valueCodec: MutationCodec<V>? = null,
    internal val conflicts: MutationConflictRegistration<K, V>? = null,
    // The factory binds the delegated Store's LocalOnly read and clear door through these
    // lambdas after the delegate exists; direct engine tests bind their own.
    private val baseReader: suspend (K) -> V? = { null },
    private val freshnessBarrier: suspend (K) -> Unit = {},
    private val absentAdoption: suspend (K) -> Unit = {},
    // Stamps journal enqueue times and normalized failure times. The factory threads the
    // builder-retained clock or the mutations-owned system default.
    private val wallClock: WallClock = MutationsSystemWallClock,
    // An internal-only deterministic seam. The default preserves production behavior; the
    // focused backoff tests supply a seeded generator before eligibility policy is materialized.
    private val backoffRandom: Random = Random.Default,
    // The stable installation identity: one fixed string per engine, persisted on the durable
    // client row and stamped into every push, attempt, and failure.
    internal val clientId: String = "client-0",
    private val namespaceInvalidation: suspend (StoreNamespace) -> Unit = {},
) {
    private val mutations = Mutex()
    private val durableAckAdmission = Mutex()
    private val hydration = Mutex()
    private val globalDrainPass = Mutex()
    private val retirementPass = Mutex()
    private val drainScheduler = IdentityDrainScheduler()
    private val namespaceDrainScheduler = NamespaceDrainScheduler()
    private val durableJournal =
        (journal as? StorageBackedMutationJournal<V>)?.takeIf { candidate ->
            candidate.hydrateOnFirstUse && valueCodec != null
        }
    private var hydrated = durableJournal == null
    private var nextMutationSequence = 0L
    private val signalSink = MutableSharedFlow<StoreKey>(replay = 1)
    private val poisonSink =
        MutableSharedFlow<PoisonedIntent>(
            replay = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private lateinit var handle: StoreWriteHandle<K, V>

    // In-memory effect snapshots captured before first push; execution reads the durable rows.
    private val effectSnapshots = AtomicMutableMap<String, List<MutationEffectRecord>>()
    private val durableEffectRows = AtomicMutableMap<String, List<StoredEffectRecord>>()
    private val hydratedTombstones = AtomicMutableList<MutationKeyTombstoneRecord>()

    // In-memory execution bookkeeping for truthful inspection. On a hydrated engine it is
    // rebuilt from the durable records.
    private val phases = AtomicMutableMap<String, MutationExecutionPhase>()
    private val completedAttempts = AtomicMutableMap<String, Int>()
    private val drainFailures = AtomicMutableList<MutationFailure>()
    private val durableExecutions = AtomicMutableMap<String, MutationExecutionRecord>()
    private val durableAttempts = AtomicMutableMap<String, MutationAttemptRecord>()
    private val durableAcks = AtomicMutableMap<String, MutationAckRecord>()
    private val acceptedIntentIdentities = AtomicMutableMap<String, KeyIdentity>()
    private val deadLettersByMutationId = AtomicMutableMap<String, DeadLetter>()
    private val codecBlockedMutationIds = AtomicMutableSet<String>()
    private val preAckParkCandidates = AtomicMutableMap<String, PreAckParkCandidate>()
    private val legacyPendingPresentAcks =
        AtomicMutableMap<String, LegacyPendingPresentAck<K, V>>()

    // An ack target must remain stable even when the first legal receipt transaction rolls back.
    // This process-local guard is read and written only while [mutations] is held; durable ACKED
    // generations never consult it because they are never retransmitted.
    private val ackEffectiveTargetsByIdempotencyKey = mutableMapOf<String, KeyIdentity>()

    // Guarded by [mutations]. A just-committed enqueue cannot drain until its revision and
    // MutationEnqueued publication complete in the same non-cancellable handoff.
    private val pendingEnqueuePublications = mutableSetOf<String>()

    // The live key map is CACHE ONLY. Global drain's correctness path is the durable journal
    // identity plus the resolver; every cache hit is revalidated against the exact pair before
    // reuse and a drifted entry is discarded. Facade terminal-alias resolution shares this
    // cache under the same revalidation rule: path compression may be cached but never
    // replaces durable edges.
    private val liveKeys = MutableStateFlow<Map<KeyIdentity, K>>(emptyMap())

    // The in-memory normalized alias table fronting the durable edges: same-namespace
    // full-pair redirects with PENDING/ACTIVE states plus generation-idempotency receipts.
    private val aliasRouter =
        InMemoryAliasRouter(
            storage =
                (journal as? StorageBackedMutationJournal<V>)?.storage
                    ?: InMemoryMutationJournalStorage(),
            runtimeState =
                (journal as? StorageBackedMutationJournal<V>)?.runtimeState
                    ?: MutationRuntimeState<Any>(),
        )

    // Lost-wakeup-free per-terminal-identity signals, both mutation-owned stateful monotonic
    // counters:
    // - [aliasRevisionSignals] advances only inside the NonCancellable retirement/activation
    //   handoff of a redirect's source identity; a live facade stream re-resolves on a strictly
    //   newer value and swaps delegates.
    // - [resolutionPulseSignals] advances on every activation AND on every explicit non-stream
    //   facade/drain resolution attempt-or-success for an identity; a facade stream waiting
    //   after a resolver failure retries on a strictly newer value. A stream's own attempt
    //   never advances either signal.
    private val aliasRevisionSignals =
        MutableStateFlow<Map<KeyIdentity, MutableStateFlow<Long>>>(emptyMap())
    private val resolutionPulseSignals =
        MutableStateFlow<Map<KeyIdentity, MutableStateFlow<Long>>>(emptyMap())

    // The contiguous locally retired prefix, in-memory form, advertised on pushes.
    private val retirementState = MutableStateFlow(RetirementState())
    private var retiredThroughSequence: Long
        get() = retirementState.value.retiredThroughSequence
        set(value) {
            retirementState.update { current -> current.copy(retiredThroughSequence = value) }
        }
    private var serverConfirmedRetiredThroughSequence: Long
        get() = retirementState.value.serverConfirmedRetiredThroughSequence
        set(value) {
            retirementState.update { current ->
                current.copy(serverConfirmedRetiredThroughSequence = value)
            }
        }
    private val retiredSequences = AtomicMutableSet<Long>()

    internal val changes: SharedFlow<StoreKey> = signalSink.asSharedFlow()
    internal val poisoned: SharedFlow<PoisonedIntent> = poisonSink.asSharedFlow()

    /** The advisory lifecycle bus republished by the facade. */
    internal val eventBus = MutationEventBus()

    /**
     * Projects confirmed residence through the current pending intents.
     *
     * Store stamps a changed projection with `OVERLAY` origin, zero age, and no staleness.
     * `OVERLAY` is therefore the pending-write affordance; staleness is not. The shared [changes]
     * stream remains live and never completes or fails.
     */
    internal val overlay: Overlay<K, V> =
        object : Overlay<K, V> {
            override fun apply(
                key: K,
                base: V?,
            ): V? = projectAll(key, base)

            override val changes: Flow<StoreKey> = this@MutationEngine.changes
        }

    /**
     * Rebuilds every process cache from one coherent durable snapshot. Codec work runs only after
     * the storage transaction has returned; hydration never emits an overlay or alias revision.
     */
    internal suspend fun ensureHydrated() {
        val durable = durableJournal ?: return
        hydration.withLock {
            if (hydrated) return
            val snapshot = durable.readDurableSnapshot()
            val hydratedAliases = snapshot.aliases.toAliasEdgesBySource()
            val activeTombstoneWatermarks =
                snapshot.tombstones
                    .asSequence()
                    .filter { tombstone ->
                        tombstone.createdByClientId == clientId &&
                            tombstone.state == MutationTombstoneState.ACTIVE
                    }
                    .groupBy { tombstone ->
                        terminalIdentity(tombstone.identity(), hydratedAliases)
                    }
                    .mapValues { (_, tombstones) ->
                        tombstones.maxOf { tombstone -> tombstone.createdBySequence }
                    }
            val executionsBySequence = snapshot.executions.associateBy { it.clientSequence }
            val attemptsByIdentity =
                snapshot.attempts.associateBy { it.clientSequence to it.generation }
            validateUniqueDurableNamespaceOwners(snapshot.executions, attemptsByIdentity)
            hydratedTombstones.clear()
            hydratedTombstones.addAll(snapshot.tombstones)
            val failuresById = snapshot.failures.associateBy(MutationFailureRecord::failureId)
            val effectsBySequence = snapshot.effects.groupBy { it.clientSequence }
            val entriesByIdentity = linkedMapOf<KeyIdentity, MutableList<JournalEntry<V>>>()
            val existingCodecFailures =
                snapshot.failures
                    .asSequence()
                    .filter { failure -> failure.kind == MutationFailureKind.CODEC }
                    .map { failure ->
                        Triple(failure.clientSequence, failure.generation, failure.detail)
                    }
                    .toMutableSet()

            phases.clear()
            completedAttempts.clear()
            durableExecutions.clear()
            durableAttempts.clear()
            durableAcks.clear()
            acceptedIntentIdentities.clear()
            deadLettersByMutationId.clear()
            codecBlockedMutationIds.clear()
            preAckParkCandidates.clear()
            legacyPendingPresentAcks.clear()
            effectSnapshots.clear()
            durableEffectRows.clear()
            drainFailures.clear()
            retiredSequences.clear()

            val client = snapshot.client
            if (client == null) {
                durable.installHydratedState(emptyMap(), hydratedAliases)
                aliasRouter.resetAfterHydration()
                nextMutationSequence = 0L
                retiredThroughSequence = 0L
                serverConfirmedRetiredThroughSequence = 0L
                hydrated = true
                return
            }

            for (failure in snapshot.failures) {
                drainFailures += failure.toPublicFailure()
            }
            for (ack in snapshot.acks) {
                val intent = snapshot.intents.firstOrNull { it.clientSequence == ack.clientSequence }
                if (intent != null) durableAcks[intent.mutationId] = ack
            }

            for (intent in snapshot.intents.sortedBy { it.clientSequence }) {
                val execution = executionsBySequence[intent.clientSequence] ?: continue
                val phase = execution.phase.toEnginePhase()
                durableExecutions[intent.mutationId] = execution
                phases[intent.mutationId] = phase
                completedAttempts[intent.mutationId] = execution.attempt
                if (execution.currentGeneration > 0) {
                    attemptsByIdentity[intent.clientSequence to execution.currentGeneration]?.let {
                        durableAttempts[intent.mutationId] = it
                    }
                }
                effectSnapshots[intent.mutationId] =
                    effectsBySequence[intent.clientSequence]
                        .orEmpty()
                        .sortedBy { it.effectIndex }
                        .map { effect ->
                            MutationEffectRecord(
                                kind =
                                    when (effect.kind) {
                                        MutationEffectKind.KEY -> MutationEffectRecordKind.KEY
                                        MutationEffectKind.NAMESPACE -> MutationEffectRecordKind.NAMESPACE
                                    },
                                namespace = effect.namespace,
                                canonicalId = effect.canonicalId,
                            )
                        }
                durableEffectRows[intent.mutationId] =
                    effectsBySequence[intent.clientSequence]
                        .orEmpty()
                        .sortedBy { it.effectIndex }

                val attemptedIdentity =
                    durableAttempts[intent.mutationId]?.let { attempt ->
                        KeyIdentity(attempt.effectiveNamespace, attempt.effectiveCanonicalId)
                    } ?: KeyIdentity(intent.namespace, intent.canonicalId)
                val effectiveIdentity = terminalIdentity(attemptedIdentity, hydratedAliases)

                if (phase == MutationExecutionPhase.PARKED) {
                    val failure = execution.activeFailureId?.let(failuresById::get) ?: continue
                    deadLettersByMutationId[intent.mutationId] =
                        DeadLetter(
                            namespace = effectiveIdentity.namespace,
                            canonicalId = effectiveIdentity.canonicalId,
                            mutationId = intent.mutationId,
                            mutatorId = intent.mutatorId,
                            generation = execution.currentGeneration,
                            attempts = execution.attempt,
                            failure = failure.toPublicFailure(),
                            parkedAtEpochMillis = failure.occurredAt,
                        )
                    continue
                }
                if (phase == MutationExecutionPhase.RETIRED) {
                    if (intent.clientSequence > client.retiredThroughSequence) {
                        retiredSequences += intent.clientSequence
                    }
                    continue
                }
                if (
                    intent.clientSequence <=
                    (activeTombstoneWatermarks[effectiveIdentity] ?: 0L)
                ) {
                    continue
                }
                if (
                    execution.currentGeneration == 0 &&
                    execution.phase == StoredExecutionPhase.UNPREPARED
                ) {
                    acceptedIntentIdentities[intent.mutationId] =
                        KeyIdentity(intent.namespace, intent.canonicalId)
                }

                var blocked = false
                val preAck =
                    phase == MutationExecutionPhase.UNPREPARED ||
                        phase == MutationExecutionPhase.READY ||
                        phase == MutationExecutionPhase.INFLIGHT ||
                        phase == MutationExecutionPhase.REFRESH_REQUIRED
                val parkOnDrain = phase.permitsPreAckParking(MutationFailureKind.CODEC)
                val registration = registry.registrations[intent.mutatorId]
                val args =
                    if (registration == null) {
                        if (preAck) {
                            classifyHydrationCodecFailure(
                                intent = intent,
                                generation = execution.currentGeneration,
                                detail = HYDRATION_FAILURE_DETAIL_MUTATOR_MISSING,
                                message =
                                    "No mutator '${intent.mutatorId}' is registered for durable " +
                                        "hydration.",
                                parkOnDrain = parkOnDrain,
                                existing = existingCodecFailures,
                            )
                            blocked = true
                        }
                        UnavailableMutationArgs
                    } else {
                        try {
                            registration.decodeArgs(intent.mutatorVersion, intent.argsBlob)
                        } catch (failure: Throwable) {
                            if (failure is CancellationException) throw failure
                            if (preAck) {
                                classifyHydrationCodecFailure(
                                    intent = intent,
                                    generation = execution.currentGeneration,
                                    detail = HYDRATION_FAILURE_DETAIL_ARGS,
                                    message =
                                        failure.message
                                            ?: "Mutation args codec failed without a message.",
                                parkOnDrain = parkOnDrain,
                                    existing = existingCodecFailures,
                                )
                                blocked = true
                            }
                            UnavailableMutationArgs
                        }
                    }

                if (!blocked) {
                    try {
                        when (phase) {
                            MutationExecutionPhase.READY,
                            MutationExecutionPhase.INFLIGHT,
                            MutationExecutionPhase.REFRESH_REQUIRED,
                            -> {
                                val attempt = requireNotNull(durableAttempts[intent.mutationId])
                                val codec = checkNotNull(valueCodec)
                                attempt.decodeBase(codec)
                                attempt.decodeMine(codec)
                            }

                            MutationExecutionPhase.ACKED -> {
                                val ack = requireNotNull(durableAcks[intent.mutationId])
                                if (ack.authoritativePresence == MutationPresenceState.PRESENT) {
                                    checkNotNull(valueCodec).decodeCopied(
                                        ack.valueCodecVersion,
                                        checkNotNull(ack.authoritativeBlob),
                                    )
                                }
                            }

                            MutationExecutionPhase.UNPREPARED,
                            MutationExecutionPhase.EFFECTS_PENDING,
                            MutationExecutionPhase.PARKED,
                            MutationExecutionPhase.RETIRED,
                            -> Unit
                        }
                    } catch (failure: Throwable) {
                        if (failure is CancellationException) throw failure
                        val postAck = phase == MutationExecutionPhase.ACKED
                        classifyHydrationCodecFailure(
                            intent = intent,
                            generation = execution.currentGeneration,
                            detail =
                                if (postAck) {
                                    HYDRATION_FAILURE_DETAIL_VALUE_ACKED
                                } else {
                                    HYDRATION_FAILURE_DETAIL_VALUE_PRE_ACK
                                },
                            message =
                                failure.message
                                    ?: "Mutation value codec failed without a message.",
                            parkOnDrain = parkOnDrain,
                            existing = existingCodecFailures,
                        )
                        blocked = true
                    }
                }
                if (blocked) codecBlockedMutationIds += intent.mutationId
                entriesByIdentity
                    .getOrPut(effectiveIdentity) { mutableListOf() }
                    .add(
                        JournalEntry(
                            mutationId = intent.mutationId,
                            mutatorId = intent.mutatorId,
                            args = args,
                            clientSequence = intent.clientSequence,
                            createdAtEpochMillis = intent.createdAt,
                            durableClientSequence = intent.clientSequence,
                        ),
                    )
            }

            durable.installHydratedState(entriesByIdentity, hydratedAliases)
            aliasRouter.resetAfterHydration()
            nextMutationSequence = client.lastAllocatedSequence
            retiredThroughSequence = client.retiredThroughSequence
            serverConfirmedRetiredThroughSequence = client.serverConfirmedRetiredThroughSequence
            hydrated = true
        }
    }

    /** Rejects ambiguous restart authority before any hydrated runtime state is published. */
    private fun validateUniqueDurableNamespaceOwners(
        executions: List<MutationExecutionRecord>,
        attemptsByIdentity: Map<Pair<Long, Int>, MutationAttemptRecord>,
    ) {
        val ownersByNamespace = linkedMapOf<String, Long>()
        executions
            .asSequence()
            .filter { execution -> execution.ownsNamespaceAuthority() }
            .sortedBy { execution -> execution.clientSequence }
            .forEach { execution ->
                val attempt =
                    requireNotNull(
                        attemptsByIdentity[
                            execution.clientSequence to execution.currentGeneration
                        ],
                    ) {
                        "Namespace authority owner ${execution.clientSequence} has no current " +
                            "generation ${execution.currentGeneration} attempt."
                    }
                val previous =
                    ownersByNamespace.put(
                        attempt.effectiveNamespace,
                        execution.clientSequence,
                    )
                check(previous == null) {
                    "Durable mutation journal has multiple authority owners for client " +
                        "'$clientId' namespace '${attempt.effectiveNamespace}': sequences " +
                        "$previous and ${execution.clientSequence}."
                }
            }
    }

    private suspend fun classifyHydrationCodecFailure(
        intent: MutationIntentRecord,
        generation: Int,
        detail: String,
        message: String,
        parkOnDrain: Boolean,
        existing: MutableSet<Triple<Long, Int, String>>,
    ) {
        codecBlockedMutationIds += intent.mutationId
        if (parkOnDrain) {
            preAckParkCandidates[intent.mutationId] =
                PreAckParkCandidate(
                    kind = MutationFailureKind.CODEC,
                    detail = detail,
                    message = message,
                )
            return
        }
        if (!existing.add(Triple(intent.clientSequence, generation, detail))) return
        val durable = requireNotNull(durableJournal)
        val occurredAt = wallClock.nowEpochMillis()
        val normalized =
            sanitizedMutationFailure(
                kind = MutationFailureKind.CODEC,
                detail = detail,
                message = message,
                occurredAtEpochMillis = occurredAt,
            )
        durable.storage.transaction { transaction ->
            transaction.appendFailure(
                clientId = clientId,
                clientSequence = intent.clientSequence,
                generation = generation,
                kind = normalized.kind,
                detail = normalized.detail,
                message = normalized.message,
                occurredAt = occurredAt,
            )
        }
        drainFailures += normalized
    }

    internal suspend fun <A : Any> mutate(
        key: K,
        ref: MutatorRef<K, V, A>,
        args: A,
    ): String {
        ensureHydrated()
        require(ref.ownership === registry.ownership) {
            "MutatorRef '${ref.id}' belongs to a different MutatorRegistry."
        }
        // Consumer resolution may suspend or re-enter this store, so it must remain outside the
        // global enqueue/activation gate. It still gets exactly one resolution attempt.
        val resolvedKey = requireTerminalKey(key)
        val originalIdentity = key.identity()
        val (mutationId, effectiveKey, enqueuedEvent) =
            mutations.withLock {
                // Revalidate only the immutable routing cache under the gate. If activation won
                // after the one consumer resolution above, it published its exact target K under
                // this same gate, so no second resolver attempt is needed.
                val terminal = aliasRouter.terminalOf(originalIdentity)
                val appendKey =
                    if (resolvedKey.identity() == terminal) {
                        resolvedKey
                    } else {
                        checkNotNull(cachedLiveKey(terminal)) {
                            "Alias activation published terminal identity $terminal without its " +
                                "exact canonical key."
                        }
                    }
                val identity = appendKey.identity()
                val sequence = nextMutationSequence + 1L
                val nextId = "mutation-$sequence"
                val createdAt = wallClock.nowEpochMillis()
                journal.append(
                    identity,
                    JournalEntry(
                        mutationId = nextId,
                        mutatorId = ref.id,
                        args = args,
                        clientSequence = sequence,
                        createdAtEpochMillis = createdAt,
                    ),
                )
                nextMutationSequence = sequence
                cacheLiveKey(identity, appendKey)
                if (durableJournal != null) acceptedIntentIdentities[nextId] = identity
                pendingEnqueuePublications += nextId
                phases[nextId] = MutationExecutionPhase.UNPREPARED
                durableExecutions[nextId] =
                    MutationExecutionRecord(
                        clientId = clientId,
                        clientSequence = sequence,
                        phase = StoredExecutionPhase.UNPREPARED,
                        currentGeneration = 0,
                        attempt = 0,
                        lastAttemptAt = null,
                        activeFailureId = null,
                        retiredAt = null,
                    )
                Triple(
                    nextId,
                    appendKey,
                    MutationEnqueued(
                        mutationId = nextId,
                        identity = identity.toEventIdentity(),
                        occurredAtEpochMillis = createdAt,
                        clientSequence = sequence,
                        mutatorId = ref.id,
                    ),
                )
            }
        withContext(NonCancellable) {
            signalSink.emit(effectiveKey)
            eventBus.tryEmit(enqueuedEvent)
            mutations.withLock {
                check(pendingEnqueuePublications.remove(mutationId)) {
                    "Enqueue publication barrier was not retained for '$mutationId'."
                }
            }
        }
        return mutationId
    }

    internal fun bind(handle: StoreWriteHandle<K, V>) {
        check(!this::handle.isInitialized) {
            "Mutation engine is already bound."
        }
        this.handle = handle
    }

    /**
     * One idempotent keyed foreground pass: captures the unprojected confirmed base through the
     * ordered `status -> LocalOnly` loop, then pushes the pending FIFO prefix once with no retry
     * or backoff. A pre-ack codec/projection failure parks that head and continues its same-key
     * suffix. A key without pending work is a no-op that reads nothing. The facade resolves the
     * terminal alias identity before calling this; a mid-pass activation re-homes the pass to
     * the canonical key and continues the sequence-merged prefix there.
     */
    internal suspend fun drain(
        key: K,
        ensureOpen: () -> Unit = {},
    ) {
        var retainedFailure: RetryablePostAckFailure? = null
        try {
            ensureHydrated()
            drainIdentity(
                key = key,
                overrideBackoff = true,
                ensureOpen = ensureOpen,
            )
        } catch (failure: RetryablePostAckFailure) {
            retainedFailure = failure
        }
        flushRetirementCheckpoint()
        retainedFailure?.let { failure -> throw failure.exposed }
    }

    /**
     * One idempotent global foreground pass: enumerates durable identities from the journal,
     * reconstructs each `K` through the retained resolver with exact-pair validation, and
     * continues past identities that fail to resolve after parking exactly one owned durable
     * pre-ack head (or retaining the precursor carrier when no such head exists). A sanctioned
     * retryable post-ack failure retains its owner and does not block another namespace in this
     * captured pass. Processing is deterministic by durable client sequence within an identity
     * and enumerates a captured identity snapshot in first-enqueue order; no cross-key order is
     * promised.
     */
    internal suspend fun drain(ensureOpen: () -> Unit = {}) {
        ensureHydrated()
        globalDrainPass.withLock {
            val sequenceBound = mutations.withLock { nextMutationSequence }
            val identities = orderedGlobalDrainIdentities(journal.identities().toList())
            for (initialIdentity in identities) {
                try {
                    drainGlobalIdentity(initialIdentity, sequenceBound, ensureOpen)
                } catch (failure: RetryablePostAckFailure) {
                    if (!retainsRetryablePostAckOwner(failure)) {
                        throw failure.exposed
                    }
                }
            }
        }
        flushRetirementCheckpoint()
    }

    /**
     * Flushes the current contiguous local retirement prefix without holding a mutation,
     * identity, namespace, runtime-cache, or retirement lock across consumer transport.
     * Confirmation commits before a separate prune transaction. Non-cancellation failures are
     * advisory client-scoped events only; they never create an intent failure row.
     */
    private suspend fun flushRetirementCheckpoint() {
        val durable = durableJournal ?: return
        val beforeRequest = retirementState.value
        val requestedThrough = beforeRequest.retiredThroughSequence
        if (requestedThrough == 0L) return
        val request =
            MutationRetirement(
                clientId = clientId,
                retiredThroughSequence = requestedThrough,
            )

        val validatedConfirmation =
            if (requestedThrough > beforeRequest.serverConfirmedRetiredThroughSequence) {
                val ack =
                    try {
                        server.retire(request)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Throwable) {
                        emitCheckpointFailure(
                            request = request,
                            kind = MutationFailureKind.TRANSPORT,
                            detail = "retire-failed",
                            failure = failure,
                        )
                        return
                    }
                try {
                    validateRetirementAck(
                        request = request,
                        ack = ack,
                        previousConfirmedThroughSequence =
                            beforeRequest.serverConfirmedRetiredThroughSequence,
                    )
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    emitCheckpointFailure(
                        request = request,
                        kind = MutationFailureKind.PROTOCOL,
                        detail = "retire-ack-invalid",
                        failure = failure,
                    )
                    return
                }
            } else {
                null
            }

        retirementPass.withLock {
            val currentConfirmed = serverConfirmedRetiredThroughSequence
            if (validatedConfirmation != null && validatedConfirmation > currentConfirmed) {
                val persisted =
                    try {
                        durable.storage.transaction { transaction ->
                            transaction.confirmRetiredThrough(
                                clientId = clientId,
                                requestedThroughSequence = request.retiredThroughSequence,
                                serverConfirmedThroughSequence = validatedConfirmation,
                            )
                        }
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Throwable) {
                        emitCheckpointFailure(
                            request = request,
                            kind = MutationFailureKind.PERSISTENCE,
                            detail = "retire-confirmation-failed",
                            failure = failure,
                        )
                        return@withLock
                    }
                serverConfirmedRetiredThroughSequence =
                    persisted.serverConfirmedRetiredThroughSequence
                eventBus.tryEmit(
                    MutationCheckpointConfirmed(
                        occurredAtEpochMillis = wallClock.nowEpochMillis(),
                        clientId = clientId,
                        requestedThroughSequence = request.retiredThroughSequence,
                        confirmedThroughSequence =
                            persisted.serverConfirmedRetiredThroughSequence,
                    ),
                )
            }

            val persistedConfirmed = serverConfirmedRetiredThroughSequence
            if (persistedConfirmed == 0L) return@withLock
            try {
                durable.storage.transaction { transaction ->
                    transaction.prune(
                        clientId = clientId,
                        serverConfirmedRetiredThroughSequence = persistedConfirmed,
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                emitCheckpointFailure(
                    request = request,
                    kind = MutationFailureKind.PERSISTENCE,
                    detail = "retire-prune-failed",
                    failure = failure,
                )
            }
        }
    }

    /** Emits one sanitized ephemeral checkpoint failure; no intent or durable row is invented. */
    private fun emitCheckpointFailure(
        request: MutationRetirement,
        kind: MutationFailureKind,
        detail: String,
        failure: Throwable,
    ) {
        val occurredAt = wallClock.nowEpochMillis()
        val normalized =
            sanitizedMutationFailure(
                kind = kind,
                detail = detail,
                message = failure.message ?: "Retirement checkpoint failed without a message.",
                occurredAtEpochMillis = occurredAt,
            )
        eventBus.tryEmit(
            MutationCheckpointFailed(
                occurredAtEpochMillis = occurredAt,
                clientId = clientId,
                requestedThroughSequence = request.retiredThroughSequence,
                failure = normalized,
            ),
        )
    }

    /** Drops every cached resolution so the next global drain must reconstruct. */
    internal fun clearLiveKeyCache() {
        liveKeys.value = emptyMap()
    }

    /**
     * The normalized in-memory drain failure carriers recorded so far: resolver `IDENTITY`
     * failures and alias `PROTOCOL` failures. No original `Throwable` or `StoreError` is
     * retained.
     */
    internal fun drainFailuresForInspection(): List<MutationFailure> = drainFailures.toList()

    internal suspend fun pending(key: K): List<PendingIntent> {
        ensureHydrated()
        return pendingForIdentity(key.identity())
    }

    /** Snapshot terminal routing and rows together in durable client-sequence order. */
    internal fun pendingForIdentity(identity: KeyIdentity): List<PendingIntent> {
        val cache = journal as? StorageBackedMutationJournal<V>
        if (cache == null) {
            return pendingRows(aliasRouter.terminalOf(identity))
        }
        val snapshot = cache.runtimeSnapshot()
        val terminal = terminalIdentity(identity, snapshot.aliases)
        return replayableEntries(terminal, snapshot.entries[terminal].orEmpty())
            .sortedBy { entry -> entry.clientSequence }
            .map { entry -> pendingRow(terminal, entry) }
    }

    /**
     * Snapshot rows for every durable identity in durable client-sequence order: the
     * per-client sequence is the FIFO and watermark unit, so the global view sorts by it.
     */
    internal suspend fun pendingWrites(): List<PendingIntent> {
        ensureHydrated()
        val cache = journal as? StorageBackedMutationJournal<V>
        val entriesByIdentity =
            cache?.runtimeSnapshot()?.entries
                ?: journal.identities().associateWith(journal::pendingSnapshot)
        return entriesByIdentity
            .flatMap { (identity, entries) ->
                replayableEntries(identity, entries).map { entry ->
                    entry.clientSequence to pendingRow(identity, entry)
                }
            }
            .sortedBy { (clientSequence, _) -> clientSequence }
            .map { (_, row) -> row }
    }

    /**
     * Durably parked intents only, ordered by park time. The in-memory failure carriers are
     * exposed by [drainFailuresForInspection].
     */
    internal suspend fun deadLetters(): List<DeadLetter> {
        ensureHydrated()
        return deadLettersByMutationId.values.sortedBy { it.parkedAtEpochMillis }
    }

    internal fun projectAll(
        key: K,
        base: V?,
    ): V? {
        var projected: MutationPresence<V> = presenceOf(base)
        for (entry in orderedPending(key.identity())) {
            projected = project(projected, entry).value
        }
        return (projected as? MutationPresence.Present)?.value
    }

    // -----------------------------------------------------------------------------------------
    // Canonical alias routing doors used by the facade.
    // -----------------------------------------------------------------------------------------

    /** The terminal identity for [identity] under the active alias edges. */
    internal fun terminalIdentityOf(identity: KeyIdentity): KeyIdentity =
        aliasRouter.terminalOf(identity)

    /**
     * The mutation-owned stateful alias revision for [identity]. It advances synchronously
     * inside the NonCancellable retirement/activation handoff of a redirect whose source is
     * [identity]; a live facade stream re-resolves on a strictly newer value.
     */
    internal fun aliasRevision(identity: KeyIdentity): StateFlow<Long> =
        aliasRevisionSignal(identity)

    /**
     * The mutation-owned stateful resolution pulse for [identity]. It advances on alias
     * activation and on every explicit non-stream facade/drain resolution attempt-or-success;
     * a facade stream waiting after a resolver failure retries on a strictly newer value and a
     * stream's own attempt never advances it.
     */
    internal fun resolutionPulse(identity: KeyIdentity): StateFlow<Long> =
        resolutionPulseSignal(identity)

    /** Active subscriptions on [identity]'s resolution pulse; release-verification test door. */
    internal fun resolutionPulseSubscriptions(identity: KeyIdentity): Int =
        resolutionPulseSignal(identity).subscriptionCount.value

    /**
     * One terminal-identity resolution attempt for a facade entry point: the given
     * key IS the terminal key when no active alias redirects its identity — the resolver is
     * never consulted on that happy path. An aliased identity reuses the exact-pair-revalidated
     * live cache or reconstructs the terminal `K` through the retained resolver with
     * [requireResolvedKey]. This attempt never advances any signal; `CancellationException` is
     * always rethrown.
     */
    internal suspend fun resolveTerminalKey(key: K): TerminalKeyResolution<K> {
        ensureHydrated()
        val terminal = aliasRouter.terminalOf(key.identity())
        if (terminal == key.identity()) {
            return TerminalKeyResolution.Resolved(key, terminal)
        }
        cachedLiveKey(terminal)?.let { cached ->
            return TerminalKeyResolution.Resolved(cached, terminal)
        }
        val requested = MutationKeyIdentity(terminal.namespace, terminal.canonicalId)
        val resolved =
            try {
                keyResolver.resolve(requested)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                return TerminalKeyResolution.Failed(
                    identity = terminal,
                    detail = DRAIN_FAILURE_DETAIL_RESOLVER_THROW,
                    message =
                        failure.message
                            ?: "MutationKeyResolver threw without a message for " +
                                "(${terminal.namespace}, ${terminal.canonicalId}).",
                    cause = failure,
                )
            }
        return try {
            TerminalKeyResolution.Resolved(
                key =
                    requireResolvedKey(requested, resolved).also { validated ->
                        cacheLiveKey(terminal, validated)
                    },
                identity = terminal,
            )
        } catch (failure: IllegalStateException) {
            TerminalKeyResolution.Failed(
                identity = terminal,
                detail =
                    if (resolved == null) {
                        DRAIN_FAILURE_DETAIL_RESOLVER_NULL
                    } else {
                        DRAIN_FAILURE_DETAIL_IDENTITY_MISMATCH
                    },
                message = failure.message.orEmpty(),
                cause = null,
            )
        }
    }

    /**
     * The suspending-facade resolution door: one attempt, then the sanctioned
     * `StoreResults.exception(StoreResults.conversionError(message, cause), cause)` on failure.
     * The attempt-or-success advances the identity's resolution pulse so live streams waiting on
     * that identity wake and retry.
     */
    internal suspend fun requireTerminalKey(key: K): K {
        val resolution = resolveTerminalKey(key)
        bumpResolutionPulse(resolution.identity)
        return when (resolution) {
            is TerminalKeyResolution.Resolved -> resolution.key
            is TerminalKeyResolution.Failed ->
                throw StoreResults.exception(
                    StoreResults.conversionError(resolution.message, resolution.cause),
                    resolution.cause,
                )
        }
    }

    /**
     * The keyed-drain resolution door: a failed terminal resolution parks one owned durable
     * pre-ack head and returns normally. A codec-less engine, a missing head, or a later-owned head
     * preserves the normalized precursor carrier. The attempt-or-success advances the identity's
     * resolution pulse exactly like every non-stream facade attempt.
     */
    internal suspend fun drainKeyResolvedOrRecord(
        key: K,
        ensureOpen: () -> Unit = {},
    ) {
        var retainedFailure: RetryablePostAckFailure? = null
        try {
            ensureHydrated()
            withDurableNamespaceLease(key.identity()) {
                drainKeyResolvedOrRecordUnderLease(key, ensureOpen)
            }
        } catch (failure: RetryablePostAckFailure) {
            retainedFailure = failure
        }
        flushRetirementCheckpoint()
        retainedFailure?.let { failure -> throw failure.exposed }
    }

    private suspend fun drainKeyResolvedOrRecordUnderLease(
        key: K,
        ensureOpen: () -> Unit,
    ) {
        val sequenceBound = mutations.withLock { nextMutationSequence }
        var lockIdentity = key.identity()
        while (true) {
            var activeTerminal: KeyIdentity? = null
            var passRehome: DrainRehome<K, V>? = null
            var completed = false
            drainScheduler.withIdentity(lockIdentity) {
                ensureOpen()
                val terminal = aliasRouter.terminalOf(lockIdentity)
                if (terminal != lockIdentity) {
                    activeTerminal = terminal
                    return@withIdentity
                }

                val resolution = resolveTerminalKey(key)
                if (resolution.identity != lockIdentity) {
                    activeTerminal = resolution.identity
                    return@withIdentity
                }
                bumpResolutionPulse(resolution.identity)
                when (resolution) {
                    is TerminalKeyResolution.Resolved -> {
                        passRehome =
                            drainIdentityLocked(
                                key = resolution.key,
                                lockKey = resolution.key,
                                sequenceBound = sequenceBound,
                                overrideBackoff = true,
                                initialEntry = null,
                            )
                    }
                    is TerminalKeyResolution.Failed -> {
                        val head =
                            nextEligibleHead(
                                identity = resolution.identity,
                                sequenceBound = sequenceBound,
                                overrideBackoff = true,
                            )
                        val entry = head?.entry
                        val phase = head?.phase
                        if (
                            durableJournal != null &&
                            entry != null &&
                            (phase ?: MutationExecutionPhase.UNPREPARED)
                                .permitsPreAckParking(MutationFailureKind.IDENTITY)
                        ) {
                            parkDurablePreAck(
                                identity = resolution.identity,
                                entry = entry,
                                kind = MutationFailureKind.IDENTITY,
                                detail = resolution.detail,
                                message = resolution.message,
                            )
                        } else {
                            // Keep the no-head/later-owned posture. Only an affected executable
                            // pre-ack row may create a durable terminal failure.
                            recordNormalizedFailure(
                                kind = MutationFailureKind.IDENTITY,
                                detail = DRAIN_FAILURE_DETAIL_KEYED_TERMINAL_UNRESOLVED,
                                message = resolution.message,
                            )
                        }
                    }
                }
                completed = true
            }
            val terminal = activeTerminal
            if (terminal != null) {
                lockIdentity = terminal
                continue
            }
            val rehome = passRehome
            if (rehome != null) {
                continueRehomedDrain(rehome, overrideBackoff = true, ensureOpen = ensureOpen)
            }
            if (completed) return
        }
    }

    private fun aliasRevisionSignal(identity: KeyIdentity): MutableStateFlow<Long> =
        signalFor(aliasRevisionSignals, identity)

    private fun resolutionPulseSignal(identity: KeyIdentity): MutableStateFlow<Long> =
        signalFor(resolutionPulseSignals, identity)

    private fun signalFor(
        signals: MutableStateFlow<Map<KeyIdentity, MutableStateFlow<Long>>>,
        identity: KeyIdentity,
    ): MutableStateFlow<Long> {
        signals.value[identity]?.let { return it }
        val candidate = MutableStateFlow(0L)
        signals.update { current ->
            if (identity in current) current else current + (identity to candidate)
        }
        return checkNotNull(signals.value[identity])
    }

    private fun bumpResolutionPulse(identity: KeyIdentity) {
        resolutionPulseSignal(identity).update { revision -> revision + 1L }
    }

    private fun cachedLiveKey(identity: KeyIdentity): K? {
        val cached = liveKeys.value[identity] ?: return null
        if (
            cached.namespace.value == identity.namespace &&
            cached.canonicalId() == identity.canonicalId
        ) {
            return cached
        }
        liveKeys.update { current ->
            if (current[identity] === cached) current - identity else current
        }
        return null
    }

    private fun cacheLiveKey(
        identity: KeyIdentity,
        key: K,
    ) {
        check(
            key.namespace.value == identity.namespace &&
                key.canonicalId() == identity.canonicalId,
        ) {
            "Live key (${key.namespace.value}, ${key.canonicalId()}) does not match cache " +
                "identity (${identity.namespace}, ${identity.canonicalId})."
        }
        liveKeys.update { current -> current + (identity to key) }
    }

    private fun orderedPending(identity: KeyIdentity): List<JournalEntry<V>> =
        replayableEntries(identity, journal.pendingSnapshot(identity))
            .sortedBy { entry -> entry.clientSequence }

    private fun replayableEntries(
        identity: KeyIdentity,
        entries: List<JournalEntry<V>>,
    ): List<JournalEntry<V>> {
        val terminal = aliasRouter.terminalOf(identity)
        val watermark =
            hydratedTombstones
                .filter { tombstone ->
                    tombstone.createdByClientId == clientId &&
                        tombstone.state == MutationTombstoneState.ACTIVE &&
                        aliasRouter.terminalOf(tombstone.identity()) == terminal
                }.maxOfOrNull { tombstone -> tombstone.createdBySequence }
                ?: return entries
        return entries.filter { entry -> entry.clientSequence > watermark }
    }

    private fun pendingRows(identity: KeyIdentity): List<PendingIntent> =
        orderedPending(identity).map { entry -> pendingRow(identity, entry) }

    private fun pendingRow(
        identity: KeyIdentity,
        entry: JournalEntry<V>,
    ): PendingIntent =
        PendingIntent(
            namespace = identity.namespace,
            canonicalId = identity.canonicalId,
            mutationId = entry.mutationId,
            mutatorId = entry.mutatorId,
            state = publicStateOf(entry.mutationId),
            attempt = completedAttempts[entry.mutationId] ?: 0,
            createdAtEpochMillis = entry.createdAtEpochMillis,
        )

    private fun publicStateOf(mutationId: String): MutationPendingState =
        (phases[mutationId] ?: MutationExecutionPhase.UNPREPARED).toPendingStateOrNull()
            // A journalled row can never be PARKED or RETIRED; PENDING is the total mapping's
            // only legal fallback for an unobserved phase.
            ?: MutationPendingState.PENDING

    /**
     * The exact-pair resolution for global drain. Cache hits are revalidated verbatim before
     * reuse; non-cancellation failures return a structured normalized carrier so the caller can
     * park an owned durable pre-ack head or preserve the in-memory precursor. The original
     * throwable is never persisted. `CancellationException` is always rethrown.
     */
    private suspend fun resolveForDrain(identity: KeyIdentity): TerminalKeyResolution<K> {
        cachedLiveKey(identity)?.let { cached ->
            return TerminalKeyResolution.Resolved(cached, identity)
        }
        val requested = MutationKeyIdentity(identity.namespace, identity.canonicalId)
        val resolved =
            try {
                keyResolver.resolve(requested)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                return TerminalKeyResolution.Failed(
                    identity = identity,
                    detail = DRAIN_FAILURE_DETAIL_RESOLVER_THROW,
                    message = failure.message ?: "MutationKeyResolver threw without a message.",
                    cause = failure,
                )
            }
        return try {
            TerminalKeyResolution.Resolved(
                key =
                    requireResolvedKey(requested, resolved).also { validated ->
                        cacheLiveKey(identity, validated)
                    },
                identity = identity,
            )
        } catch (failure: IllegalStateException) {
            TerminalKeyResolution.Failed(
                identity = identity,
                detail =
                    if (resolved == null) {
                        DRAIN_FAILURE_DETAIL_RESOLVER_NULL
                    } else {
                        DRAIN_FAILURE_DETAIL_IDENTITY_MISMATCH
                    },
                message = failure.message.orEmpty(),
                cause = null,
            )
        }
    }

    private fun recordNormalizedFailure(
        kind: MutationFailureKind,
        detail: String,
        message: String,
    ) {
        drainFailures +=
            sanitizedMutationFailure(
                kind = kind,
                detail = detail,
                message = message,
                occurredAtEpochMillis = wallClock.nowEpochMillis(),
            )
    }

    /**
     * Captures C8's causal owner order for one global pass. Namespaces that already have an
     * owner contribute only that owner's residence; their independent nonowners wait for the
     * next trigger even when the owner retires during this pass. Other namespaces retain the
     * journal's captured first-enqueue order.
     */
    private fun orderedGlobalDrainIdentities(
        captured: List<KeyIdentity>,
    ): List<KeyIdentity> {
        val owners = durableNamespaceOwners().values.sortedBy { owner -> owner.clientSequence }
        if (owners.isEmpty()) return captured
        val ownedNamespaces = owners.mapTo(mutableSetOf()) { owner -> owner.identity.namespace }
        return (owners.map { owner -> owner.identity } +
            captured.filter { identity -> identity.namespace !in ownedNamespaces })
            .distinct()
    }

    /**
     * Runs one durable namespace pass when its process lease is unowned or belongs to the same
     * effective identity. Same-effective contenders reserve and wait; distinct contenders return
     * without resolving consumer code or beginning transport. Durable ownership is rechecked
     * after the lease is acquired.
     */
    private suspend fun withDurableNamespaceLease(
        requestedIdentity: KeyIdentity,
        block: suspend () -> Unit,
    ) {
        val durable = durableJournal
        if (durable == null) {
            block()
            return
        }
        namespaceDrainScheduler.tryWithNamespace(
            requestedIdentity = requestedIdentity,
            sameEffectiveIdentity = { leasedIdentity, contenderIdentity ->
                val aliases = durable.runtimeState.aliasesSnapshot()
                terminalIdentity(leasedIdentity, aliases) ==
                    terminalIdentity(contenderIdentity, aliases)
            },
        ) {
            val owner = durableNamespaceOwners()[requestedIdentity.namespace]
            if (
                owner != null &&
                owner.identity != aliasRouter.terminalOf(requestedIdentity)
            ) {
                return@tryWithNamespace
            }
            block()
        }
    }

    /** Derives the one owner per namespace exclusively from existing execution/attempt rows. */
    private fun durableNamespaceOwners(): Map<String, DurableNamespaceOwner> {
        if (durableJournal == null) return emptyMap()
        val attemptsBySequence =
            durableAttempts.values.associateBy { attempt -> attempt.clientSequence }
        val owners = linkedMapOf<String, DurableNamespaceOwner>()
        durableExecutions.values
            .asSequence()
            .filter { execution -> execution.ownsNamespaceAuthority() }
            .sortedBy { execution -> execution.clientSequence }
            .forEach { execution ->
                val attempt =
                    requireNotNull(attemptsBySequence[execution.clientSequence]) {
                        "Namespace authority owner ${execution.clientSequence} has no current " +
                            "attempt."
                    }
                val identity =
                    aliasRouter.terminalOf(
                        KeyIdentity(attempt.effectiveNamespace, attempt.effectiveCanonicalId),
                    )
                val owner =
                    DurableNamespaceOwner(
                        clientSequence = execution.clientSequence,
                        identity = identity,
                    )
                val previous = owners.put(attempt.effectiveNamespace, owner)
                check(previous == null) {
                    "Durable mutation journal has multiple authority owners for client " +
                        "'$clientId' namespace '${attempt.effectiveNamespace}': sequences " +
                        "${previous?.clientSequence} and ${execution.clientSequence}."
                }
            }
        return owners
    }

    /**
     * The keyed foreground pass. The base is captured once through the ordered loop; after each
     * in-pass adoption the acknowledged authoritative presence becomes the next entry's base and
     * its metadata is re-read from the retained bookkeeper, where confirmation has already been
     * recorded and therefore cannot lead the adopted value.
     *
     * Alias interaction: the pass drains the durable-client-sequence prefix that existed
     * when it started, selecting the lowest pending sequence at the CURRENT effective identity
     * each step. When a Present acknowledgement's redirect activates mid-pass, the pass re-homes
     * to the canonical key and continues the sequence-merged siblings there. An acknowledgement
     * that fails alias-protocol validation records one normalized `PROTOCOL` carrier, returns
     * the intent to `READY` with a completed-attempt fact, performs no adoption, and halts this
     * key's pass.
     */
    private suspend fun drainGlobalIdentity(
        initialIdentity: KeyIdentity,
        sequenceBound: Long,
        ensureOpen: () -> Unit,
    ) {
        withDurableNamespaceLease(initialIdentity) {
            drainGlobalIdentityUnderLease(initialIdentity, sequenceBound, ensureOpen)
        }
    }

    private suspend fun drainGlobalIdentityUnderLease(
        initialIdentity: KeyIdentity,
        sequenceBound: Long,
        ensureOpen: () -> Unit,
    ) {
        var lockIdentity = initialIdentity
        while (true) {
            var activeTerminal: KeyIdentity? = null
            var passRehome: DrainRehome<K, V>? = null
            drainScheduler.withIdentity(lockIdentity) {
                ensureOpen()
                val terminal = aliasRouter.terminalOf(lockIdentity)
                if (terminal != lockIdentity) {
                    activeTerminal = terminal
                    return@withIdentity
                }

                // Eligibility is checked while holding the effective-identity lease and
                // before resolver work. The selected entry carries this pass's single jitter
                // draw into the resolved path.
                val head =
                    nextEligibleHead(lockIdentity, sequenceBound, overrideBackoff = false)
                        ?: return@withIdentity
                val entry = head.entry
                when (val resolution = resolveForDrain(lockIdentity)) {
                    is TerminalKeyResolution.Resolved -> {
                        if (resolution.identity != lockIdentity) {
                            activeTerminal = resolution.identity
                            return@withIdentity
                        }
                        passRehome =
                            drainIdentityLocked(
                                key = resolution.key,
                                lockKey = resolution.key,
                                sequenceBound = sequenceBound,
                                overrideBackoff = false,
                                initialEntry = entry,
                            )
                    }
                    is TerminalKeyResolution.Failed -> {
                        if (resolution.identity != lockIdentity) {
                            activeTerminal = resolution.identity
                            return@withIdentity
                        }
                        val phase = head.phase
                        if (
                            durableJournal != null &&
                                phase.permitsPreAckParking(MutationFailureKind.IDENTITY)
                        ) {
                            parkDurablePreAck(
                                identity = lockIdentity,
                                entry = entry,
                                kind = MutationFailureKind.IDENTITY,
                                detail = resolution.detail,
                                message = resolution.message,
                            )
                        } else {
                            // Preserve the codec-less precursor and later-owned refresh/post-ack
                            // posture while continuing independent identities.
                            recordNormalizedFailure(
                                kind = MutationFailureKind.IDENTITY,
                                detail = resolution.detail,
                                message = resolution.message,
                            )
                        }
                    }
                }
            }
            val terminal = activeTerminal
            if (terminal != null) {
                lockIdentity = terminal
                continue
            }
            val rehome = passRehome
            if (rehome != null) {
                continueRehomedDrain(
                    initial = rehome,
                    overrideBackoff = false,
                    ensureOpen = ensureOpen,
                )
            }
            return
        }
    }

    private suspend fun drainIdentity(
        key: K,
        overrideBackoff: Boolean,
        sequenceBound: Long? = null,
        initialEntry: JournalEntry<V>? = null,
        ensureOpen: () -> Unit = {},
    ) {
        withDurableNamespaceLease(key.identity()) {
            val capturedBound = sequenceBound ?: mutations.withLock { nextMutationSequence }
            drainIdentityFromCursor(
                processingKey = key,
                lockKey = key,
                sequenceBound = capturedBound,
                overrideBackoff = overrideBackoff,
                initialEntry = initialEntry,
                ensureOpen = ensureOpen,
            )
        }
    }

    private suspend fun continueRehomedDrain(
        initial: DrainRehome<K, V>,
        overrideBackoff: Boolean,
        ensureOpen: () -> Unit,
    ) {
        val targetKey =
            resolveExecutionIdentity(
                acknowledged = initial.targetIdentity,
                postAckEntry = initial.entry,
            )
        drainIdentityFromCursor(
            processingKey = initial.processingKey,
            lockKey = targetKey,
            sequenceBound = initial.sequenceBound,
            overrideBackoff = overrideBackoff,
            initialEntry = initial.entry,
            ensureOpen = ensureOpen,
        )
    }

    private suspend fun drainIdentityFromCursor(
        processingKey: K,
        lockKey: K,
        sequenceBound: Long,
        overrideBackoff: Boolean,
        initialEntry: JournalEntry<V>?,
        ensureOpen: () -> Unit,
    ) {
        var currentProcessingKey = processingKey
        var currentLockKey = lockKey
        var currentEntry = initialEntry
        while (true) {
            var activeTargetIdentity: KeyIdentity? = null
            var passRehome: DrainRehome<K, V>? = null
            val leaseIdentities =
                setOf(currentProcessingKey.identity(), currentLockKey.identity())
            drainScheduler.withIdentities(leaseIdentities) {
                ensureOpen()
                val terminal = aliasRouter.terminalOf(currentLockKey.identity())
                if (terminal != currentLockKey.identity()) {
                    activeTargetIdentity = terminal
                    return@withIdentities
                }
                if (currentProcessingKey.identity() != currentLockKey.identity()) {
                    val processingTerminal =
                        aliasRouter.terminalOf(currentProcessingKey.identity())
                    if (processingTerminal == currentLockKey.identity()) {
                        // Another continuation completed this source cursor while we waited for
                        // the ordered source+target lease. Continue the captured pass at the
                        // target suffix instead of replaying a retired source row.
                        currentProcessingKey = currentLockKey
                        currentEntry = null
                    } else if (processingTerminal != currentProcessingKey.identity()) {
                        activeTargetIdentity = processingTerminal
                        return@withIdentities
                    }
                }
                passRehome =
                    drainIdentityLocked(
                        key = currentProcessingKey,
                        lockKey = currentLockKey,
                        sequenceBound = sequenceBound,
                        overrideBackoff = overrideBackoff,
                        initialEntry = currentEntry,
                    )
            }
            val targetIdentity = activeTargetIdentity
            if (targetIdentity != null) {
                // Resolution is consumer code and may suspend or re-enter drain. It must run
                // only after the entire ordered lease set has been released.
                val target =
                    resolveExecutionIdentity(
                        acknowledged = targetIdentity,
                        postAckEntry = currentEntry,
                    )
                if (currentProcessingKey.identity() == currentLockKey.identity()) {
                    currentProcessingKey = target
                    currentEntry = null
                }
                currentLockKey = target
                continue
            }
            val rehome = passRehome ?: return
            val targetKey =
                resolveExecutionIdentity(
                    acknowledged = rehome.targetIdentity,
                    postAckEntry = rehome.entry,
                )
            currentProcessingKey = rehome.processingKey
            currentLockKey = targetKey
            currentEntry = rehome.entry
        }
    }

    private suspend fun drainIdentityLocked(
        key: K,
        lockKey: K,
        sequenceBound: Long,
        overrideBackoff: Boolean,
        initialEntry: JournalEntry<V>?,
    ): DrainRehome<K, V>? =
        if (durableJournal == null) {
            drainIdentityLegacy(key, lockKey, sequenceBound, overrideBackoff, initialEntry)
        } else {
            drainIdentityDurable(key, lockKey, sequenceBound, overrideBackoff, initialEntry)
        }

    /** The path taken by direct, codec-less engine constructions in module tests. */
    private suspend fun drainIdentityLegacy(
        key: K,
        lockKey: K,
        sequenceBound: Long,
        overrideBackoff: Boolean,
        initialEntry: JournalEntry<V>?,
    ): DrainRehome<K, V>? {
        val lockIdentity = lockKey.identity()
        var currentKey = key
        var entry =
            nextEligibleHead(
                identity = currentKey.identity(),
                sequenceBound = sequenceBound,
                overrideBackoff = overrideBackoff,
                initialEntry = initialEntry,
            )?.entry ?: return null
        var captured: CapturedBase<V>? = null
        while (true) {
            val pendingAck = legacyPendingPresentAcks[entry.mutationId]
            if (pendingAck != null) {
                val terminalTarget = aliasRouter.terminalOf(pendingAck.targetKey.identity())
                if (terminalTarget != lockIdentity) {
                    return DrainRehome(
                        processingKey = pendingAck.sourceKey,
                        targetIdentity = terminalTarget,
                        entry = entry,
                        sequenceBound = sequenceBound,
                    )
                }
                val normalizedPending =
                    if (lockKey === pendingAck.targetKey) {
                        pendingAck
                    } else {
                        LegacyPendingPresentAck(
                            sourceKey = pendingAck.sourceKey,
                            targetKey = lockKey,
                            authoritative = pendingAck.authoritative,
                            etag = pendingAck.etag,
                        ).also { normalized ->
                            legacyPendingPresentAcks[entry.mutationId] = normalized
                        }
                    }
                val adoption = resumeLegacyPresentAck(entry, normalizedPending)
                currentKey = adoption.effectiveKey
                val next =
                    nextEligibleHead(
                        currentKey.identity(),
                        sequenceBound,
                        overrideBackoff,
                    )?.entry ?: return null
                captured =
                    CapturedBase(
                        presence = MutationPresence.Present(adoption.adopted),
                        meta = snapshotMeta(bookkeeper.status(currentKey)?.meta),
                    )
                entry = next
                continue
            }

            val currentBase = captured ?: captureBase(currentKey).also { captured = it }
            val outcome = project(currentBase.presence, entry)
            if (!outcome.advanced) return null
            val mine = outcome.value
            if (!captureEffects(currentKey, entry)) return null
            val push =
                buildPush(
                    currentKey,
                    entry,
                    currentBase.presence,
                    mine,
                    currentBase.meta,
                )
            phases[entry.mutationId] = MutationExecutionPhase.INFLIGHT
            val ack =
                try {
                    server.push(push)
                } catch (failure: Throwable) {
                    // Transport cancellation is not failure: INFLIGHT stays intact and the next
                    // pass replays the same immutable generation. Only a non-cancellation
                    // failure records a completed-attempt fact and returns to READY.
                    if (failure is CancellationException) throw failure
                    completedAttempts[entry.mutationId] =
                        (completedAttempts[entry.mutationId] ?: 0) + 1
                    phases[entry.mutationId] = MutationExecutionPhase.READY
                    return null
                }
            when (ack) {
                is MutationPresentAck -> {
                    val staged =
                        stageLegacyPresentAck(currentKey, entry, push.idempotencyKey, ack)
                            ?: return null
                    val terminalTarget = aliasRouter.terminalOf(staged.targetKey.identity())
                    if (terminalTarget != lockIdentity) {
                        return DrainRehome(
                            processingKey = staged.sourceKey,
                            targetIdentity = terminalTarget,
                            entry = entry,
                            sequenceBound = sequenceBound,
                        )
                    }
                    val normalizedStaged =
                        if (lockKey === staged.targetKey) {
                            staged
                        } else {
                            LegacyPendingPresentAck(
                                sourceKey = staged.sourceKey,
                                targetKey = lockKey,
                                authoritative = staged.authoritative,
                                etag = staged.etag,
                            ).also { normalized ->
                                legacyPendingPresentAcks[entry.mutationId] = normalized
                            }
                        }
                    val adoption = resumeLegacyPresentAck(entry, normalizedStaged)
                    currentKey = adoption.effectiveKey
                    val next =
                        nextEligibleHead(
                            currentKey.identity(),
                            sequenceBound,
                            overrideBackoff,
                        )?.entry ?: return null
                    captured =
                        CapturedBase(
                            presence = MutationPresence.Present(adoption.adopted),
                            meta = snapshotMeta(bookkeeper.status(currentKey)?.meta),
                        )
                    entry = next
                    continue
                }
                is MutationAbsentAck -> {
                    adoptAbsent(currentKey, entry)
                    captured = CapturedBase(MutationPresence.Absent, null)
                    entry =
                        nextEligibleHead(
                            currentKey.identity(),
                            sequenceBound,
                            overrideBackoff,
                        )?.entry ?: return null
                    continue
                }
            }
        }
    }

    /**
     * Durable foreground pass. Phase dispatch happens before any consumer callback: READY and
     * INFLIGHT replay their immutable attempt, ACKED resumes adoption, EFFECTS_PENDING resumes
     * only retirement finalization, and only UNPREPARED may capture/project/prepare a generation.
     */
    private suspend fun drainIdentityDurable(
        key: K,
        lockKey: K,
        sequenceBound: Long,
        overrideBackoff: Boolean,
        initialEntry: JournalEntry<V>?,
    ): DrainRehome<K, V>? {
        val lockIdentity = lockKey.identity()
        var currentKey = key
        var entryHint = initialEntry
        while (true) {
            val head =
                nextEligibleHead(
                    identity = currentKey.identity(),
                    sequenceBound = sequenceBound,
                    overrideBackoff = overrideBackoff,
                    initialEntry = entryHint,
                ) ?: return null
            val entry = head.entry
            val phase = head.phase
            entryHint = null
            val parkCandidate = preAckParkCandidates[entry.mutationId]
            if (parkCandidate != null) {
                // The candidate's failure kind determines which pre-ack phases may park.
                if (!phase.permitsPreAckParking(parkCandidate.kind)) return null
                parkDurablePreAck(
                    identity = currentKey.identity(),
                    entry = entry,
                    kind = parkCandidate.kind,
                    detail = parkCandidate.detail,
                    message = parkCandidate.message,
                )
                continue
            }
            if (entry.mutationId in codecBlockedMutationIds) {
                if (
                    phase != MutationExecutionPhase.ACKED ||
                    !probeDurableAckCodec(entry)
                ) {
                    return null
                }
                codecBlockedMutationIds.remove(entry.mutationId)
            }
            when (phase) {
                MutationExecutionPhase.UNPREPARED -> {
                    when (prepareDurableAttempt(currentKey, entry)) {
                        DurablePreparationOutcome.BLOCKED -> return null
                        DurablePreparationOutcome.PARKED -> continue
                        DurablePreparationOutcome.PREPARED -> markDurableInflight(entry)
                    }
                }

                MutationExecutionPhase.READY -> markDurableInflight(entry)

                MutationExecutionPhase.INFLIGHT -> Unit

                MutationExecutionPhase.REFRESH_REQUIRED -> {
                    val attempt = requireNotNull(durableAttempts[entry.mutationId])
                    when (resumeDurableConflict(currentKey, entry, attempt)) {
                        DurableConflictOutcome.RETRY_PREPARED -> markDurableInflight(entry)
                        DurableConflictOutcome.PARKED,
                        DurableConflictOutcome.RETIRED,
                        -> continue
                    }
                }

                MutationExecutionPhase.ACKED -> {
                    val effectiveIdentity = durableAckEffectiveIdentity(currentKey, entry)
                    if (effectiveIdentity != lockIdentity) {
                        return DrainRehome(
                            processingKey = currentKey,
                            targetIdentity = effectiveIdentity,
                            entry = entry,
                            sequenceBound = sequenceBound,
                        )
                    }
                    val effectiveKey =
                        if (effectiveIdentity == currentKey.identity()) currentKey else lockKey
                    currentKey = resumeDurableAck(currentKey, effectiveKey, entry) ?: return null
                    continue
                }

                MutationExecutionPhase.EFFECTS_PENDING -> {
                    val effectiveIdentity = durableAckEffectiveIdentity(currentKey, entry)
                    if (effectiveIdentity != lockIdentity) {
                        return DrainRehome(
                            processingKey = currentKey,
                            targetIdentity = effectiveIdentity,
                            entry = entry,
                            sequenceBound = sequenceBound,
                        )
                    }
                    val effectiveKey =
                        if (effectiveIdentity == currentKey.identity()) currentKey else lockKey
                    currentKey =
                        resumeDurableEffectsPending(currentKey, effectiveKey, entry) ?: return null
                    continue
                }

                MutationExecutionPhase.PARKED,
                MutationExecutionPhase.RETIRED,
                -> return null
            }

            val attempt = requireNotNull(durableAttempts[entry.mutationId])
            val push = buildPushFromDurableAttempt(currentKey, entry, attempt)
            val execution = requireNotNull(durableExecutions[entry.mutationId])
            // Completed durable attempts + 1; an INFLIGHT replay re-emits the same ordinal.
            eventBus.tryEmit(
                MutationAttempted(
                    mutationId = push.mutationId,
                    identity = push.identity,
                    occurredAtEpochMillis = wallClock.nowEpochMillis(),
                    generation = push.generation,
                    attempt = execution.attempt + 1,
                ),
            )
            val ack =
                try {
                    server.push(push)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    val conflict = (failure as? StoreException)?.error as? StoreError.Conflict
                    if (conflict == null) {
                        recordDurableTransportFailure(entry, failure)
                        return null
                    }
                    when (recordDurableConflict(entry, attempt, conflict)) {
                        DurableConflictReceiptOutcome.REFRESH_REQUIRED -> return null
                        DurableConflictReceiptOutcome.PARKED -> continue
                    }
                }
            when (ack) {
                is MutationPresentAck -> {
                    when (recordDurableAckReceipt(entry, attempt, ack)) {
                        DurableAckReceiptOutcome.ACKED -> Unit
                        DurableAckReceiptOutcome.PARKED -> continue
                        DurableAckReceiptOutcome.HALTED -> return null
                    }
                }
                is MutationAbsentAck -> {
                    when (recordDurableAckReceipt(entry, attempt, ack)) {
                        DurableAckReceiptOutcome.ACKED -> Unit
                        DurableAckReceiptOutcome.PARKED -> continue
                        DurableAckReceiptOutcome.HALTED -> return null
                    }
                }
            }
            // ACK persistence is the handoff point. Dispatching the persisted phase on the next
            // loop iteration either adopts under this lease or requests a canonical re-home.
            entryHint = entry
        }
    }

    private suspend fun prepareDurableAttempt(
        key: K,
        entry: JournalEntry<V>,
    ): DurablePreparationOutcome {
        val durable = requireNotNull(durableJournal)
        val captured = captureBase(key)
        val projected = project(captured.presence, entry)
        projected.failure?.let { failure ->
            parkDurablePreAck(
                identity = key.identity(),
                entry = entry,
                kind = MutationFailureKind.PROJECTION,
                detail = DRAIN_FAILURE_DETAIL_PROJECTION_THROW,
                message = failure.message ?: "Mutation projection failed without a message.",
            )
            return DurablePreparationOutcome.PARKED
        }
        if (!projected.advanced) return DurablePreparationOutcome.BLOCKED
        val preconditionMeta =
            when (
                val selection =
                    selectPreconditionMeta(
                        key = key,
                        entry = entry,
                        generation = 1,
                        base = captured.presence,
                        mine = projected.value,
                        capturedMeta = captured.meta,
                    )
            ) {
                is PreconditionSelection.Selected -> selection.meta
                is PreconditionSelection.Failed -> {
                    parkDurableConflictFailure(
                        identity = key.identity(),
                        entry = entry,
                        detail = "selector-failed",
                        message =
                            selection.failure.message
                                ?: "Mutation precondition selector failed without a message.",
                    )
                    return DurablePreparationOutcome.PARKED
                }
            }
        val effects = evaluateEffects(key, entry) ?: return DurablePreparationOutcome.BLOCKED
        val preparedAt = wallClock.nowEpochMillis()
        val attempt =
            buildDurableAttempt(
                key = key,
                entry = entry,
                generation = 1,
                base = captured.presence,
                mine = projected.value,
                preconditionMeta = preconditionMeta,
                preparedAt = preparedAt,
            )
        val storedEffects =
            effects.mapIndexed { index, effect ->
                StoredEffectRecord(
                    clientId = clientId,
                    clientSequence = entry.durableClientSequence,
                    effectIndex = index,
                    kind =
                        when (effect.kind) {
                            MutationEffectRecordKind.KEY -> MutationEffectKind.KEY
                            MutationEffectRecordKind.NAMESPACE -> MutationEffectKind.NAMESPACE
                        },
                    namespace = effect.namespace,
                    canonicalId = effect.canonicalId,
                    createdAt = preparedAt,
                    disposition = MutationEffectDisposition.PENDING,
                    completedAt = null,
                )
            }
        val previous = requireNotNull(durableExecutions[entry.mutationId])
        val ready =
            previous.copyExecution(
                phase = StoredExecutionPhase.READY,
                currentGeneration = 1,
                attempt = 0,
                lastAttemptAt = null,
            )
        durable.storage.transaction { transaction ->
            transaction.insertAttempt(attempt)
            storedEffects.forEach(transaction::insertEffect)
            transaction.advanceExecution(ready)
        }
        effectSnapshots[entry.mutationId] = effects
        durableEffectRows[entry.mutationId] = storedEffects
        durableAttempts[entry.mutationId] = attempt
        acceptedIntentIdentities.remove(entry.mutationId)
        durableExecutions[entry.mutationId] = ready
        phases[entry.mutationId] = MutationExecutionPhase.READY
        completedAttempts[entry.mutationId] = 0
        return DurablePreparationOutcome.PREPARED
    }

    /** Selects and freezes metadata once for a newly prepared semantic generation. */
    private fun selectPreconditionMeta(
        key: K,
        entry: JournalEntry<V>,
        generation: Int,
        base: MutationPresence<V>,
        mine: MutationPresence<V>,
        capturedMeta: StoreMeta?,
    ): PreconditionSelection {
        val selector = conflicts?.precondition
            ?: return PreconditionSelection.Selected(snapshotMeta(capturedMeta))
        val candidate =
            MutationPreconditionCandidate(
                identity = MutationKeyIdentity(key.namespace.value, key.canonicalId()),
                key = key,
                mutationId = entry.mutationId,
                generation = generation,
                base = copiedPresence(base),
                mine = copiedPresence(mine),
                capturedMeta = snapshotMeta(capturedMeta),
            )
        return try {
            PreconditionSelection.Selected(snapshotMeta(selector(candidate)))
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            PreconditionSelection.Failed(failure)
        }
    }

    /** Builds one immutable attempt row; all consumer-owned values and metadata are copied. */
    private fun buildDurableAttempt(
        key: K,
        entry: JournalEntry<V>,
        generation: Int,
        base: MutationPresence<V>,
        mine: MutationPresence<V>,
        preconditionMeta: StoreMeta?,
        preparedAt: Long,
    ): MutationAttemptRecord {
        val codec = checkNotNull(valueCodec)
        return MutationAttemptRecord(
            clientId = clientId,
            clientSequence = entry.durableClientSequence,
            generation = generation,
            effectiveNamespace = key.namespace.value,
            effectiveCanonicalId = key.canonicalId(),
            valueCodecVersion = valueCodecVersion,
            basePresence = base.toPresenceState(),
            baseBlob = base.encodePresentOrNull(codec),
            minePresence = mine.toPresenceState(),
            mineBlob = mine.encodePresentOrNull(codec),
            preconditionMetaPresent = preconditionMeta != null,
            preconditionWrittenAt = preconditionMeta?.writtenAtEpochMillis,
            preconditionEtag = preconditionMeta?.etag,
            advertisedRetiredThroughSequence = retiredThroughSequence,
            generationIdempotencyKey =
                "$clientId:${entry.durableClientSequence}:g$generation",
            preparedAt = preparedAt,
            conflictMetaPresent = null,
            conflictWrittenAt = null,
            conflictEtag = null,
            conflictReceivedAt = null,
        )
    }

    private suspend fun markDurableInflight(entry: JournalEntry<V>) {
        val durable = requireNotNull(durableJournal)
        val previous = requireNotNull(durableExecutions[entry.mutationId])
        val inflight = previous.copyExecution(phase = StoredExecutionPhase.INFLIGHT)
        durable.storage.transaction { it.advanceExecution(inflight) }
        durableExecutions[entry.mutationId] = inflight
        phases[entry.mutationId] = MutationExecutionPhase.INFLIGHT
    }

    private suspend fun recordDurableTransportFailure(
        entry: JournalEntry<V>,
        failure: Throwable,
    ) {
        val durable = requireNotNull(durableJournal)
        val previous = requireNotNull(durableExecutions[entry.mutationId])
        val occurredAt = wallClock.nowEpochMillis()
        val normalized =
            sanitizedMutationFailure(
                kind = MutationFailureKind.TRANSPORT,
                detail = "push-failed",
                message = failure.message ?: "Mutation transport failed without a message.",
                occurredAtEpochMillis = occurredAt,
            )
        val ready =
            previous.copyExecution(
                phase = StoredExecutionPhase.READY,
                attempt = previous.attempt + 1,
                lastAttemptAt = occurredAt,
            )
        durable.storage.transaction { transaction ->
            transaction.appendFailure(
                clientId = clientId,
                clientSequence = entry.durableClientSequence,
                generation = previous.currentGeneration,
                kind = normalized.kind,
                detail = normalized.detail,
                message = normalized.message,
                occurredAt = occurredAt,
            )
            transaction.advanceExecution(ready)
        }
        drainFailures += normalized
        durableExecutions[entry.mutationId] = ready
        phases[entry.mutationId] = MutationExecutionPhase.READY
        completedAttempts[entry.mutationId] = ready.attempt
        val attempt = requireNotNull(durableAttempts[entry.mutationId])
        eventBus.tryEmit(
            MutationFailed(
                mutationId = entry.mutationId,
                identity = attempt.toEventIdentity(),
                occurredAtEpochMillis = occurredAt,
                generation = ready.currentGeneration,
                state = MutationPendingState.PENDING,
                failure = normalized,
            ),
        )
    }

    /**
     * Records one completed server conflict. Ordinary receipts advance to REFRESH_REQUIRED with no
     * failure row. The trailing unchanged run is derived from immutable attempts after this
     * receipt is visible in the same transaction, and parks atomically on the third unchanged
     * pair.
     */
    private suspend fun recordDurableConflict(
        entry: JournalEntry<V>,
        attempt: MutationAttemptRecord,
        conflict: StoreError.Conflict,
    ): DurableConflictReceiptOutcome =
        withContext(NonCancellable) {
            val durable = requireNotNull(durableJournal)
            val previous = requireNotNull(durableExecutions[entry.mutationId])
            require(previous.phase == StoredExecutionPhase.INFLIGHT) {
                "A conflict receipt requires INFLIGHT, but was ${previous.phase}."
            }
            require(previous.currentGeneration == attempt.generation) {
                "A conflict receipt must target the current immutable generation."
            }

            val serverMeta = conflict.serverMeta
            val conflictMetaPresent = serverMeta != null
            val conflictWrittenAt = serverMeta?.writtenAtEpochMillis
            val conflictEtag = serverMeta?.etag
            val receivedAt = wallClock.nowEpochMillis()
            val receivedAttempt =
                attempt.withConflictReceipt(
                    conflictMetaPresent = conflictMetaPresent,
                    conflictWrittenAt = conflictWrittenAt,
                    conflictEtag = conflictEtag,
                    conflictReceivedAt = receivedAt,
                )
            val normalizedBoundFailure =
                sanitizedMutationFailure(
                    kind = MutationFailureKind.CONFLICT,
                    detail = "conflict-unchanged-bound",
                    message = conflict.message,
                    occurredAtEpochMillis = receivedAt,
                )

            val commit =
                durable.storage.transaction { transaction ->
                    transaction.recordConflictReceipt(receivedAttempt)
                    val trailingUnchanged =
                        transaction
                            .attempts(clientId)
                            .filter { candidate ->
                                candidate.clientSequence == entry.durableClientSequence &&
                                    candidate.generation <= receivedAttempt.generation
                            }
                            .asReversed()
                            .takeWhile { candidate ->
                                candidate.conflictMetaPresent != null &&
                                    candidate.conflictEtag == receivedAttempt.conflictEtag &&
                                    candidate.conflictWrittenAt == receivedAttempt.conflictWrittenAt
                            }.size
                    if (trailingUnchanged >= CONFLICT_UNCHANGED_BOUND) {
                        val failure =
                            transaction.appendFailure(
                                clientId = clientId,
                                clientSequence = entry.durableClientSequence,
                                generation = previous.currentGeneration,
                                kind = normalizedBoundFailure.kind,
                                detail = normalizedBoundFailure.detail,
                                message = normalizedBoundFailure.message,
                                occurredAt = receivedAt,
                            )
                        val parked =
                            previous.copyExecution(
                                phase = StoredExecutionPhase.PARKED,
                                attempt = previous.attempt + 1,
                                lastAttemptAt = receivedAt,
                                activeFailureId = failure.failureId,
                            )
                        transaction.advanceExecution(parked)
                        DurableConflictCommit(
                            attempt = receivedAttempt,
                            execution = parked,
                            failure = failure,
                        )
                    } else {
                        val refreshRequired =
                            previous.copyExecution(
                                phase = StoredExecutionPhase.REFRESH_REQUIRED,
                                attempt = previous.attempt + 1,
                                lastAttemptAt = receivedAt,
                            )
                        transaction.advanceExecution(refreshRequired)
                        DurableConflictCommit(
                            attempt = receivedAttempt,
                            execution = refreshRequired,
                            failure = null,
                        )
                    }
            }

            durableAttempts[entry.mutationId] = commit.attempt
            val conflictEvent =
                MutationConflictObserved(
                    mutationId = entry.mutationId,
                    identity = commit.attempt.toEventIdentity(),
                    occurredAtEpochMillis = checkNotNull(commit.attempt.conflictReceivedAt),
                    generation = commit.attempt.generation,
                    serverMeta = commit.attempt.conflictMetaOrNull(),
                )
            val parkedFailure = commit.failure
            if (parkedFailure == null) {
                durableExecutions[entry.mutationId] = commit.execution
                phases[entry.mutationId] = MutationExecutionPhase.REFRESH_REQUIRED
                completedAttempts[entry.mutationId] = commit.execution.attempt
                eventBus.tryEmit(conflictEvent)
                DurableConflictReceiptOutcome.REFRESH_REQUIRED
            } else {
                eventBus.tryEmit(conflictEvent)
                publishDurablePark(
                    identity = KeyIdentity(attempt.effectiveNamespace, attempt.effectiveCanonicalId),
                    entry = entry,
                    commit = DurableParkCommit(parkedFailure, commit.execution),
                )
                DurableConflictReceiptOutcome.PARKED
            }
        }

    /**
     * A conflict MustBeFresh read is a completion barrier only. `theirs` is recaptured afterward
     * by the same `status -> LocalOnly` algorithm. StoreError.Missing means authoritative Absent;
     * other refresh failures preserve the generation and backoff state.
     */
    private suspend fun resumeDurableConflict(
        key: K,
        entry: JournalEntry<V>,
        attempt: MutationAttemptRecord,
    ): DurableConflictOutcome {
        freshnessBarrier(key)
        val recaptured = captureBase(key)
        val theirs =
            CapturedBase(
                presence = copiedPresence(recaptured.presence),
                meta = snapshotMeta(recaptured.meta),
            )
        val codec = checkNotNull(valueCodec)
        val base = attempt.decodeBase(codec)
        val mine = attempt.decodeMine(codec)
        val merge = conflicts?.merge
        val resolution: MutationConflictResolution<V> =
            if (merge == null) {
                MutationConflictResolution.ServerWins
            } else {
                try {
                    merge(base, mine, copiedPresence(theirs.presence))
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    parkDurableConflictFailure(
                        identity = KeyIdentity(attempt.effectiveNamespace, attempt.effectiveCanonicalId),
                        entry = entry,
                        detail = "merge-failed",
                        message = failure.message ?: "Mutation conflict merge failed without a message.",
                    )
                    return DurableConflictOutcome.PARKED
                }
            }
        return when (resolution) {
            is MutationConflictResolution.Retry ->
                when (
                    prepareDurableRetryAttempt(
                        key = key,
                        entry = entry,
                        previousAttempt = attempt,
                        theirs = theirs,
                        mine = copiedPresence(resolution.value),
                    )
                ) {
                    DurablePreparationOutcome.PREPARED -> DurableConflictOutcome.RETRY_PREPARED
                    DurablePreparationOutcome.PARKED -> DurableConflictOutcome.PARKED
                    DurablePreparationOutcome.BLOCKED -> error("A conflict retry cannot be blocked.")
                }

            MutationConflictResolution.ServerWins -> {
                retireDurableServerWins(
                    identity = KeyIdentity(attempt.effectiveNamespace, attempt.effectiveCanonicalId),
                    entry = entry,
                )
                DurableConflictOutcome.RETIRED
            }
        }
    }

    /** Persists g+1 and READY atomically before the caller may mark it INFLIGHT or send it. */
    private suspend fun prepareDurableRetryAttempt(
        key: K,
        entry: JournalEntry<V>,
        previousAttempt: MutationAttemptRecord,
        theirs: CapturedBase<V>,
        mine: MutationPresence<V>,
    ): DurablePreparationOutcome {
        require(
            key.namespace.value == previousAttempt.effectiveNamespace &&
                key.canonicalId() == previousAttempt.effectiveCanonicalId,
        ) { "A conflict retry cannot change the durable effective identity." }
        val generation = previousAttempt.generation + 1
        val preconditionMeta =
            when (
                val selection =
                    selectPreconditionMeta(
                        key = key,
                        entry = entry,
                        generation = generation,
                        base = theirs.presence,
                        mine = mine,
                        capturedMeta = theirs.meta,
                    )
            ) {
                is PreconditionSelection.Selected -> selection.meta
                is PreconditionSelection.Failed -> {
                    parkDurableConflictFailure(
                        identity =
                            KeyIdentity(
                                previousAttempt.effectiveNamespace,
                                previousAttempt.effectiveCanonicalId,
                            ),
                        entry = entry,
                        detail = "selector-failed",
                        message =
                            selection.failure.message
                                ?: "Mutation precondition selector failed without a message.",
                    )
                    return DurablePreparationOutcome.PARKED
                }
            }
        val preparedAt = wallClock.nowEpochMillis()
        val nextAttempt =
            buildDurableAttempt(
                key = key,
                entry = entry,
                generation = generation,
                base = theirs.presence,
                mine = mine,
                preconditionMeta = preconditionMeta,
                preparedAt = preparedAt,
            )
        val durable = requireNotNull(durableJournal)
        val previousExecution = requireNotNull(durableExecutions[entry.mutationId])
        require(
            previousExecution.phase == StoredExecutionPhase.REFRESH_REQUIRED &&
                previousExecution.currentGeneration == previousAttempt.generation,
        ) { "A conflict retry requires the current REFRESH_REQUIRED generation." }
        val ready =
            previousExecution.copyExecution(
                phase = StoredExecutionPhase.READY,
                currentGeneration = generation,
                attempt = 0,
                lastAttemptAt = null,
            )
        durable.storage.transaction { transaction ->
            transaction.insertAttempt(nextAttempt)
            transaction.advanceExecution(ready)
        }
        durableAttempts[entry.mutationId] = nextAttempt
        durableExecutions[entry.mutationId] = ready
        phases[entry.mutationId] = MutationExecutionPhase.READY
        completedAttempts[entry.mutationId] = 0
        return DurablePreparationOutcome.PREPARED
    }

    /**
     * Rows 4a/4b: atomically records one normalized pre-ack failure and makes it the terminal
     * PARKED reason, then removes the row from executable projection and publishes that rebase as
     * one cancellation-safe accepted-state handoff.
     */
    private suspend fun parkDurablePreAck(
        identity: KeyIdentity,
        entry: JournalEntry<V>,
        kind: MutationFailureKind,
        detail: String,
        message: String,
    ) {
        val previous = requireNotNull(durableExecutions[entry.mutationId])
        require(previous.phase.toEnginePhase().permitsPreAckParking(kind)) {
            "Pre-ack failure $kind may not park ${entry.mutationId} from ${previous.phase}."
        }
        if (previous.phase == StoredExecutionPhase.INFLIGHT) {
            require(kind == MutationFailureKind.IDENTITY || kind == MutationFailureKind.CODEC) {
                "An abandoned INFLIGHT replay may preserve attempt facts only for IDENTITY/CODEC."
            }
        }
        parkDurableFailure(identity, entry, previous, kind, detail, message)
    }

    /** Selector and merge failures retain their conflict-owned phase guard. */
    private suspend fun parkDurableConflictFailure(
        identity: KeyIdentity,
        entry: JournalEntry<V>,
        detail: String,
        message: String,
    ) {
        val previous = requireNotNull(durableExecutions[entry.mutationId])
        require(
            previous.phase == StoredExecutionPhase.UNPREPARED ||
                previous.phase == StoredExecutionPhase.REFRESH_REQUIRED,
        ) {
            "A selector/merge conflict may park only from UNPREPARED or REFRESH_REQUIRED; " +
                "${entry.mutationId} was ${previous.phase}."
        }
        parkDurableFailure(
            identity = identity,
            entry = entry,
            previous = previous,
            kind = MutationFailureKind.CONFLICT,
            detail = detail,
            message = message,
        )
    }

    private suspend fun parkDurableFailure(
        identity: KeyIdentity,
        entry: JournalEntry<V>,
        previous: MutationExecutionRecord,
        kind: MutationFailureKind,
        detail: String,
        message: String,
    ) {
        val durable = requireNotNull(durableJournal)
        val occurredAt = wallClock.nowEpochMillis()
        val normalized =
            sanitizedMutationFailure(
                kind = kind,
                detail = detail,
                message = message,
                occurredAtEpochMillis = occurredAt,
            )

        withContext(NonCancellable) {
            val commit =
                durable.storage.transaction { transaction ->
                    val failure =
                        transaction.appendFailure(
                            clientId = clientId,
                            clientSequence = entry.durableClientSequence,
                            generation = previous.currentGeneration,
                            kind = normalized.kind,
                            detail = normalized.detail,
                            message = normalized.message,
                            occurredAt = occurredAt,
                        )
                    val parked =
                        previous.copyExecution(
                            phase = StoredExecutionPhase.PARKED,
                            activeFailureId = failure.failureId,
                        )
                    transaction.advanceExecution(parked)
                    DurableParkCommit(failure = failure, execution = parked)
                }
            publishDurablePark(identity, entry, commit)
        }
    }

    /** Publishes the runtime half of a committed PARKED accepted-state handoff. */
    private suspend fun publishDurablePark(
        identity: KeyIdentity,
        entry: JournalEntry<V>,
        commit: DurableParkCommit,
    ) {
        val publicFailure = commit.failure.toPublicFailure()
        durableExecutions[entry.mutationId] = commit.execution
        phases[entry.mutationId] = MutationExecutionPhase.PARKED
        completedAttempts[entry.mutationId] = commit.execution.attempt
        drainFailures += publicFailure
        deadLettersByMutationId[entry.mutationId] =
            DeadLetter(
                namespace = identity.namespace,
                canonicalId = identity.canonicalId,
                mutationId = entry.mutationId,
                mutatorId = entry.mutatorId,
                generation = commit.execution.currentGeneration,
                attempts = commit.execution.attempt,
                failure = publicFailure,
                parkedAtEpochMillis = commit.failure.occurredAt,
            )
        preAckParkCandidates.remove(entry.mutationId)
        codecBlockedMutationIds.remove(entry.mutationId)
        journal.retire(identity, entry.mutationId)
        signalSink.emit(ProjectionRevisionKey(identity))
        val eventIdentity =
            durableAttempts[entry.mutationId]?.toEventIdentity()
                ?: requireNotNull(acceptedIntentIdentities[entry.mutationId]).toEventIdentity()
        eventBus.tryEmit(
            MutationParked(
                mutationId = entry.mutationId,
                identity = eventIdentity,
                occurredAtEpochMillis = commit.failure.occurredAt,
                generation = commit.execution.currentGeneration,
                failure = publicFailure,
            ),
        )
        acceptedIntentIdentities.remove(entry.mutationId)
    }

    private fun buildPushFromDurableAttempt(
        key: K,
        entry: JournalEntry<V>,
        attempt: MutationAttemptRecord,
    ): MutationPush<K, V> {
        require(key.namespace.value == attempt.effectiveNamespace) {
            "Resolved mutation key namespace does not match durable attempt identity."
        }
        require(key.canonicalId() == attempt.effectiveCanonicalId) {
            "Resolved mutation key canonical id does not match durable attempt identity."
        }
        val codec = checkNotNull(valueCodec)
        return MutationPush(
            identity = MutationKeyIdentity(attempt.effectiveNamespace, attempt.effectiveCanonicalId),
            key = key,
            clientId = clientId,
            clientSequence = entry.durableClientSequence,
            retiredThroughSequence = attempt.advertisedRetiredThroughSequence,
            mutationId = entry.mutationId,
            generation = attempt.generation,
            idempotencyKey = attempt.generationIdempotencyKey,
            valueCodecVersion = attempt.valueCodecVersion,
            base = attempt.decodeBase(codec),
            mine = attempt.decodeMine(codec),
            baseMeta = attempt.preconditionMetaOrNull(),
        )
    }

    /**
     * Validates one acknowledgement target and persists the complete ACKED receipt while the
     * source lease is held. The engine-local target receipt is deliberately remembered before
     * persistence: a rolled-back first receipt still pins an exact-generation retry target.
     */
    private suspend fun recordDurableAckReceipt(
        entry: JournalEntry<V>,
        attempt: MutationAttemptRecord,
        ack: MutationAck<K, V>,
    ): DurableAckReceiptOutcome {
        val receipt:
            Pair<DurableAckReceiptDecision.Reject?, MutationAcknowledged?> =
            durableAckAdmission.withLock {
                val decision =
                    mutations.withLock {
                        decideDurableAckReceipt(entry, attempt, ack)
                    }
                when (decision) {
                    is DurableAckReceiptDecision.Commit -> {
                        val record =
                            recordDurableAck(entry, attempt, ack, decision.aliasAdmission)
                        if (ack is MutationPresentAck) {
                            ack.canonicalKey?.let { canonical ->
                                cacheLiveKey(canonical.identity(), canonical)
                            }
                        }
                        clearDurableAckTargetReceipt(decision)
                        null to
                            MutationAcknowledged(
                                mutationId = entry.mutationId,
                                identity = attempt.toEventIdentity(),
                                occurredAtEpochMillis = record.receivedAt,
                                generation = record.generation,
                                presence = record.authoritativePresence,
                            )
                    }
                    is DurableAckReceiptDecision.Reject -> decision to null
                }
            }
        val rejection = receipt.first
        if (rejection == null) {
            eventBus.tryEmit(checkNotNull(receipt.second))
            return DurableAckReceiptOutcome.ACKED
        }

        // Rejections never retain the ack admission gate across persistence or publication.
        val outcome =
            recordDurableAckProtocolRejection(
                identity = rejection.source,
                entry = entry,
                rejection = rejection.rejection,
            )
        if (outcome == DurableAckReceiptOutcome.PARKED) {
            clearDurableAckTargetReceipt(rejection)
        }
        return outcome
    }

    /** Admission and the process-local replay pin are the only work done under [mutations]. */
    private suspend fun decideDurableAckReceipt(
        entry: JournalEntry<V>,
        attempt: MutationAttemptRecord,
        ack: MutationAck<K, V>,
    ): DurableAckReceiptDecision {
        val source = KeyIdentity(attempt.effectiveNamespace, attempt.effectiveCanonicalId)
        val idempotencyKey = attempt.generationIdempotencyKey
        val admission =
            when (ack) {
                is MutationPresentAck ->
                    aliasRouter.admit(
                        source = source,
                        claimed = ack.canonicalKey?.identity(),
                        idempotencyKey = idempotencyKey,
                        createdByClientId = clientId,
                        createdBySequence = entry.durableClientSequence,
                        createdAt = wallClock.nowEpochMillis(),
                    )
                is MutationAbsentAck -> null
            }
        if (admission is AliasAdmission.Rejected) {
            return DurableAckReceiptDecision.Reject(
                source = source,
                rejection = admission,
                idempotencyKey = idempotencyKey,
                pinnedTarget = ackEffectiveTargetsByIdempotencyKey[idempotencyKey],
            )
        }
        val admitted = admission as? AliasAdmission.Admitted
        val effectiveTarget = admitted?.effectiveTarget ?: source
        val previousTarget = ackEffectiveTargetsByIdempotencyKey[idempotencyKey]
        if (previousTarget != null && previousTarget != effectiveTarget) {
            return DurableAckReceiptDecision.Reject(
                source = source,
                rejection =
                    AliasAdmission.Rejected(
                        detail = ALIAS_FAILURE_DETAIL_RETRY_TARGET_MISMATCH,
                        message =
                            "Retry of idempotency key '$idempotencyKey' acknowledged canonical " +
                                "target (${effectiveTarget.namespace}, " +
                                "${effectiveTarget.canonicalId}) but " +
                                "(${previousTarget.namespace}, ${previousTarget.canonicalId}) " +
                                "was previously acknowledged.",
                    ),
                idempotencyKey = idempotencyKey,
                pinnedTarget = previousTarget,
            )
        }
        ackEffectiveTargetsByIdempotencyKey[idempotencyKey] = effectiveTarget
        return DurableAckReceiptDecision.Commit(
            aliasAdmission = admitted,
            idempotencyKey = idempotencyKey,
            pinnedTarget = effectiveTarget,
        )
    }

    /** A terminal receipt outcome cannot retain the process-local replay pin. */
    private suspend fun clearDurableAckTargetReceipt(decision: DurableAckReceiptDecision) {
        val pinnedTarget = decision.pinnedTarget ?: return
        withContext(NonCancellable) {
            mutations.withLock {
                if (
                    ackEffectiveTargetsByIdempotencyKey[decision.idempotencyKey] == pinnedTarget
                ) {
                    ackEffectiveTargetsByIdempotencyKey.remove(decision.idempotencyKey)
                }
            }
        }
    }

    private suspend fun recordDurableAckProtocolRejection(
        identity: KeyIdentity,
        entry: JournalEntry<V>,
        rejection: AliasAdmission.Rejected,
    ): DurableAckReceiptOutcome {
        return if (
            rejection.detail == ALIAS_FAILURE_DETAIL_RETRY_TARGET_MISMATCH ||
            rejection.detail == ALIAS_FAILURE_DETAIL_RETARGET ||
            rejection.detail == ALIAS_FAILURE_DETAIL_CYCLE
        ) {
            parkDurableAckProtocolFailure(identity, entry, rejection)
            DurableAckReceiptOutcome.PARKED
        } else {
            // A cross-namespace rejection deliberately halts without parking.
            recordDurableProtocolFailure(entry, rejection)
            DurableAckReceiptOutcome.HALTED
        }
    }

    /** One PROTOCOL failure and the completed INFLIGHT attempt park atomically. */
    private suspend fun parkDurableAckProtocolFailure(
        identity: KeyIdentity,
        entry: JournalEntry<V>,
        rejection: AliasAdmission.Rejected,
    ) {
        val durable = requireNotNull(durableJournal)
        val previous = requireNotNull(durableExecutions[entry.mutationId])
        require(previous.phase == StoredExecutionPhase.INFLIGHT) {
            "Acknowledgement protocol parking requires INFLIGHT, but was ${previous.phase}."
        }
        val occurredAt = wallClock.nowEpochMillis()
        val normalized =
            sanitizedMutationFailure(
                kind = MutationFailureKind.PROTOCOL,
                detail = rejection.detail,
                message = rejection.message,
                occurredAtEpochMillis = occurredAt,
            )
        withContext(NonCancellable) {
            val commit =
                durable.storage.transaction { transaction ->
                    val failure =
                        transaction.appendFailure(
                            clientId = clientId,
                            clientSequence = entry.durableClientSequence,
                            generation = previous.currentGeneration,
                            kind = normalized.kind,
                            detail = normalized.detail,
                            message = normalized.message,
                            occurredAt = occurredAt,
                        )
                    val parked =
                        previous.copyExecution(
                            phase = StoredExecutionPhase.PARKED,
                            attempt = previous.attempt + 1,
                            lastAttemptAt = occurredAt,
                            activeFailureId = failure.failureId,
                        )
                    transaction.advanceExecution(parked)
                    DurableParkCommit(failure = failure, execution = parked)
                }
            publishDurablePark(identity, entry, commit)
        }
    }

    private suspend fun recordDurableProtocolFailure(
        entry: JournalEntry<V>,
        rejection: AliasAdmission.Rejected,
    ) {
        val durable = requireNotNull(durableJournal)
        val previous = requireNotNull(durableExecutions[entry.mutationId])
        val occurredAt = wallClock.nowEpochMillis()
        val normalized =
            sanitizedMutationFailure(
                kind = MutationFailureKind.PROTOCOL,
                detail = rejection.detail,
                message = rejection.message,
                occurredAtEpochMillis = occurredAt,
            )
        val ready =
            previous.copyExecution(
                phase = StoredExecutionPhase.READY,
                attempt = previous.attempt + 1,
                lastAttemptAt = occurredAt,
            )
        durable.storage.transaction { transaction ->
            transaction.appendFailure(
                clientId,
                entry.durableClientSequence,
                previous.currentGeneration,
                normalized.kind,
                normalized.detail,
                normalized.message,
                occurredAt,
            )
            transaction.advanceExecution(ready)
        }
        drainFailures += normalized
        durableExecutions[entry.mutationId] = ready
        phases[entry.mutationId] = MutationExecutionPhase.READY
        completedAttempts[entry.mutationId] = ready.attempt
        val attempt = requireNotNull(durableAttempts[entry.mutationId])
        eventBus.tryEmit(
            MutationFailed(
                mutationId = entry.mutationId,
                identity = attempt.toEventIdentity(),
                occurredAtEpochMillis = occurredAt,
                generation = ready.currentGeneration,
                state = MutationPendingState.PENDING,
                failure = normalized,
            ),
        )
    }

    private suspend fun recordDurableAck(
        entry: JournalEntry<V>,
        attempt: MutationAttemptRecord,
        ack: MutationAck<K, V>,
        aliasAdmission: AliasAdmission.Admitted? = null,
    ): MutationAckRecord {
        val durable = requireNotNull(durableJournal)
        val codec = checkNotNull(valueCodec)
        val receivedAt = wallClock.nowEpochMillis()
        val record =
            when (ack) {
                is MutationPresentAck ->
                    MutationAckRecord(
                        clientId,
                        entry.durableClientSequence,
                        attempt.generation,
                        MutationPresenceState.PRESENT,
                        codec.encodeCopied(ack.authoritative),
                        valueCodecVersion,
                        ack.etag,
                        ack.canonicalKey?.namespace?.value,
                        ack.canonicalKey?.canonicalId(),
                        receivedAt,
                    )
                is MutationAbsentAck ->
                    MutationAckRecord(
                        clientId,
                        entry.durableClientSequence,
                        attempt.generation,
                        MutationPresenceState.ABSENT,
                        null,
                        valueCodecVersion,
                        ack.etag,
                        null,
                        null,
                        receivedAt,
                    )
            }
        val previous = requireNotNull(durableExecutions[entry.mutationId])
        val acknowledged =
            previous.copyExecution(
                phase = StoredExecutionPhase.ACKED,
                attempt = previous.attempt + 1,
                lastAttemptAt = receivedAt,
            )
        val tombstone =
            if (ack is MutationAbsentAck) {
                MutationKeyTombstoneRecord(
                    namespace = attempt.effectiveNamespace,
                    canonicalId = attempt.effectiveCanonicalId,
                    createdByClientId = clientId,
                    createdBySequence = entry.durableClientSequence,
                    state = MutationTombstoneState.PENDING,
                    createdAt = receivedAt,
                    activatedAt = null,
                    supersededByClientId = null,
                    supersededBySequence = null,
                    supersededAt = null,
                )
            } else {
                null
            }
        durable.storage.transaction { transaction ->
            aliasAdmission?.pendingRecord?.let(transaction::insertAlias)
            tombstone?.let(transaction::insertTombstone)
            transaction.insertAck(record)
            transaction.advanceExecution(acknowledged)
        }
        if (aliasAdmission != null) {
            publishDurableAliasAdmission(aliasAdmission)
        }
        durableAcks[entry.mutationId] = record
        durableExecutions[entry.mutationId] = acknowledged
        phases[entry.mutationId] = MutationExecutionPhase.ACKED
        completedAttempts[entry.mutationId] = acknowledged.attempt
        tombstone?.let { hydratedTombstones += it }
        return record
    }

    /** Publishes only the pending edge; durable retry receipts are owned by the engine map. */
    private fun publishDurableAliasAdmission(admission: AliasAdmission.Admitted) {
        val durable = requireNotNull(durableJournal)
        durable.runtimeState.updateAliases { edges ->
            admission.pendingRecord?.let { record ->
                edges +
                    (KeyIdentity(record.sourceNamespace, record.sourceCanonicalId) to
                        checkNotNull(admission.redirect))
            } ?: edges
        }
    }

    private fun durableAckEffectiveIdentity(
        key: K,
        entry: JournalEntry<V>,
    ): KeyIdentity {
        val ack = requireNotNull(durableAcks[entry.mutationId])
        return if (
            ack.authoritativePresence == MutationPresenceState.PRESENT &&
            ack.canonicalTargetNamespace != null
        ) {
            aliasRouter.terminalOf(
                KeyIdentity(
                    ack.canonicalTargetNamespace,
                    checkNotNull(ack.canonicalTargetId),
                ),
            )
        } else {
            key.identity()
        }
    }

    /** Silently probes a hydration-blocked ACKED value without duplicating CODEC evidence. */
    private fun probeDurableAckCodec(entry: JournalEntry<V>): Boolean {
        val ack = requireNotNull(durableAcks[entry.mutationId])
        if (ack.authoritativePresence == MutationPresenceState.ABSENT) return true
        return try {
            checkNotNull(valueCodec).decodeCopied(
                ack.valueCodecVersion,
                checkNotNull(ack.authoritativeBlob),
            )
            true
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            false
        }
    }

    private suspend fun resumeDurableAck(
        key: K,
        effectiveKey: K,
        entry: JournalEntry<V>,
    ): K? {
        val ack = requireNotNull(durableAcks[entry.mutationId])
        return when (ack.authoritativePresence) {
            MutationPresenceState.PRESENT -> {
                try {
                    val authoritative =
                        checkNotNull(valueCodec).decodeCopied(
                            ack.valueCodecVersion,
                            checkNotNull(ack.authoritativeBlob),
                        )
                    handle.apply(effectiveKey, authoritative)
                    handle.confirmFresh(effectiveKey, ack.etag)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    throwPostAckFailureWithEvidence(
                        primary = failure,
                        entry = entry,
                        kind = MutationFailureKind.ADOPTION,
                        detail = "adoption-failed",
                        message =
                            failure.message ?: "Mutation adoption failed without a message.",
                    )
                }
                persistDurableEffectsPending(entry)
                resumeDurableEffectsPending(key, effectiveKey, entry)
            }
            MutationPresenceState.ABSENT -> {
                try {
                    absentAdoption(key)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    throwPostAckFailureWithEvidence(
                        primary = failure,
                        entry = entry,
                        kind = MutationFailureKind.ADOPTION,
                        detail = "adoption-failed",
                        message =
                            failure.message ?: "Mutation adoption failed without a message.",
                    )
                }
                persistDurableEffectsPending(entry)
                resumeDurableEffectsPending(key, key, entry)
            }
        }
    }

    /** Executes one stable pending-effect snapshot before attempting retirement finalization. */
    private suspend fun resumeDurableEffectsPending(
        key: K,
        effectiveKey: K,
        entry: JournalEntry<V>,
    ): K? {
        val pendingEffects =
            durableEffectRows[entry.mutationId]
                .orEmpty()
                .filter { effect -> effect.disposition == MutationEffectDisposition.PENDING }
                .sortedBy { effect -> effect.effectIndex }
        var firstFailure: RetryablePostAckFailure? = null
        for (effect in pendingEffects) {
            try {
                when (effect.kind) {
                    MutationEffectKind.KEY -> {
                        val declaredIdentity =
                            KeyIdentity(effect.namespace, checkNotNull(effect.canonicalId))
                        val targetIdentity = effectTargetIdentity(declaredIdentity, entry)
                        val targetKey =
                            resolveExecutionIdentity(
                                acknowledged = targetIdentity,
                                postAckEntry = entry,
                            )
                        executeDurableEffectTarget(entry) {
                            handle.markStale(targetKey)
                        }
                    }

                    MutationEffectKind.NAMESPACE ->
                        executeDurableEffectTarget(entry) {
                            namespaceInvalidation(StoreNamespace(effect.namespace))
                        }
                }
                persistDurableEffectApplied(entry, effect)
            } catch (failure: RetryablePostAckFailure) {
                val retained = firstFailure
                if (retained == null) {
                    firstFailure = failure
                } else if (retained.exposed !== failure.exposed) {
                    retained.exposed.addSuppressed(failure.exposed)
                }
            }
        }
        firstFailure?.let { failure -> throw failure }
        val retired =
            if (effectiveKey.identity() == key.identity()) {
                finalizeDurableRetirement(key, entry)
            } else {
                finalizeDurableAliasRetirement(key, effectiveKey, entry)
            }
        return effectiveKey.takeIf { retired }
    }

    /** Active routing plus this acknowledgement's private PENDING redirect for effect work. */
    private fun effectTargetIdentity(
        declared: KeyIdentity,
        entry: JournalEntry<V>,
    ): KeyIdentity {
        val activeTerminal = aliasRouter.terminalOf(declared)
        val attempt = requireNotNull(durableAttempts[entry.mutationId])
        val acknowledgementSource =
            KeyIdentity(attempt.effectiveNamespace, attempt.effectiveCanonicalId)
        if (activeTerminal != acknowledgementSource) return activeTerminal
        val pending = aliasRouter.edgeFor(acknowledgementSource)
        return if (
            pending != null &&
            pending.state == AliasEdgeState.PENDING &&
            pending.createdByClientId == clientId &&
            pending.createdBySequence == entry.durableClientSequence
        ) {
            aliasRouter.terminalOf(pending.target)
        } else {
            activeTerminal
        }
    }

    /** Target failures stay replayable and surface through Store's sanctioned persistence form. */
    private suspend fun executeDurableEffectTarget(
        entry: JournalEntry<V>,
        target: suspend () -> Unit,
    ) {
        try {
            target()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            val message = failure.message ?: "Mutation effect target failed without a message."
            val primary =
                if (failure is StoreException) {
                    failure
                } else {
                    StoreResults.exception(
                        StoreResults.persistenceError(message, failure),
                        failure,
                    )
                }
            throwPostAckFailureWithEvidence(
                primary = primary,
                entry = entry,
                kind = MutationFailureKind.EFFECT,
                detail = "effect-target-failed",
                message = message,
            )
        }
    }

    /** Marks one externally completed effect APPLIED; cache publication follows commit. */
    private suspend fun persistDurableEffectApplied(
        entry: JournalEntry<V>,
        effect: StoredEffectRecord,
    ) {
        val durable = requireNotNull(durableJournal)
        val applied = effect.appliedAt(wallClock.nowEpochMillis())
        retryablePostAckPersistence(entry) {
            durable.storage.transaction { transaction ->
                transaction.advanceEffect(applied)
            }
        }
        durableEffectRows[entry.mutationId] =
            durableEffectRows[entry.mutationId]
                .orEmpty()
                .map { current ->
                    if (current.effectIndex == applied.effectIndex) applied else current
                }
        val attempt = requireNotNull(durableAttempts[entry.mutationId])
        eventBus.tryEmit(
            MutationEffectApplied(
                mutationId = entry.mutationId,
                identity = attempt.toEventIdentity(),
                occurredAtEpochMillis = checkNotNull(applied.completedAt),
                generation = attempt.generation,
                effectIndex = applied.effectIndex,
            ),
        )
    }

    private suspend fun resolveExecutionIdentity(
        acknowledged: KeyIdentity,
        acknowledgedKey: K? = null,
        postAckEntry: JournalEntry<V>? = null,
    ): K {
        val terminal = aliasRouter.terminalOf(acknowledged)
        acknowledgedKey?.let { candidate ->
            if (
                candidate.namespace.value == terminal.namespace &&
                candidate.canonicalId() == terminal.canonicalId
            ) {
                cacheLiveKey(terminal, candidate)
                return candidate
            }
        }
        cachedLiveKey(terminal)?.let { cached -> return cached }
        return when (val resolution = resolveForDrain(terminal)) {
            is TerminalKeyResolution.Resolved -> resolution.key
            is TerminalKeyResolution.Failed -> {
                val heldPostAck =
                    postAckEntry?.takeIf { entry ->
                        when (durableExecutions[entry.mutationId]?.phase) {
                            StoredExecutionPhase.ACKED,
                            StoredExecutionPhase.EFFECTS_PENDING,
                            -> true
                            else -> false
                        }
                    }
                if (heldPostAck == null) {
                    resolution.cause?.let { throw it }
                    throw IllegalStateException(resolution.message)
                }
                val conversionFailure =
                    StoreResults.exception(
                        StoreResults.conversionError(resolution.message, resolution.cause),
                        resolution.cause,
                    )
                throwPostAckFailureWithEvidence(
                    primary = conversionFailure,
                    entry = heldPostAck,
                    kind = MutationFailureKind.IDENTITY,
                    detail = resolution.detail,
                    message = resolution.message,
                )
            }
        }
    }

    /** Evidence failure is secondary; only cancellation may replace the triggering failure. */
    private suspend fun throwPostAckFailureWithEvidence(
        primary: Throwable,
        entry: JournalEntry<V>,
        kind: MutationFailureKind,
        detail: String,
        message: String,
    ): Nothing {
        try {
            appendPostAckEvidence(entry, kind, detail, message)
        } catch (evidenceFailure: Throwable) {
            if (evidenceFailure is CancellationException) throw evidenceFailure
            if (evidenceFailure !== primary) primary.addSuppressed(evidenceFailure)
        }
        throw retryablePostAckFailure(entry, primary)
    }

    /** Marks only a known external or persistence failure at a retained post-ack owner. */
    private fun retryablePostAckFailure(
        entry: JournalEntry<V>,
        exposed: Throwable,
    ): RetryablePostAckFailure {
        if (exposed is CancellationException) throw exposed
        if (exposed is RetryablePostAckFailure) throw exposed
        val execution = requireNotNull(durableExecutions[entry.mutationId])
        require(
            execution.phase == StoredExecutionPhase.ACKED ||
                execution.phase == StoredExecutionPhase.EFFECTS_PENDING,
        ) {
            "Retryable post-ack failure requires ACKED or EFFECTS_PENDING, but was " +
                "${execution.phase}."
        }
        require(execution.activeFailureId == null) {
            "Retryable post-ack failure cannot retain an active parked failure."
        }
        return RetryablePostAckFailure(
            exposed = exposed,
            mutationId = entry.mutationId,
            retainedPhase = execution.phase,
        )
    }

    /** Wraps only the storage transaction, never postcommit publication or invariants around it. */
    private suspend fun <R> retryablePostAckPersistence(
        entry: JournalEntry<V>,
        transaction: suspend () -> R,
    ): R =
        try {
            transaction()
        } catch (failure: NonRetryablePostAckInvariantFailure) {
            throw failure.exposed
        } catch (failure: Throwable) {
            throw retryablePostAckFailure(entry, failure)
        }

    /** Distinguishes pure durable-shape/programming failures from adapter persistence failures. */
    private fun <R> nonRetryablePostAckInvariant(invariant: () -> R): R =
        try {
            invariant()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            if (failure is NonRetryablePostAckInvariantFailure) throw failure
            throw NonRetryablePostAckInvariantFailure(failure)
        }

    /** Global continuation is legal only while the same unparked durable owner remains held. */
    private fun retainsRetryablePostAckOwner(failure: RetryablePostAckFailure): Boolean {
        val execution = durableExecutions[failure.mutationId] ?: return false
        return execution.phase == failure.retainedPhase &&
            execution.activeFailureId == null &&
            (
                execution.phase == StoredExecutionPhase.ACKED ||
                    execution.phase == StoredExecutionPhase.EFFECTS_PENDING
            )
    }

    /**
     * Appends post-ack evidence without changing execution, attempts, routing, or membership.
     * The inspection carrier is published only after the evidence transaction commits.
     */
    private suspend fun appendPostAckEvidence(
        entry: JournalEntry<V>,
        kind: MutationFailureKind,
        detail: String,
        message: String,
    ) {
        require(
            kind == MutationFailureKind.IDENTITY ||
                kind == MutationFailureKind.CODEC ||
                kind == MutationFailureKind.ADOPTION ||
                kind == MutationFailureKind.EFFECT,
        ) { "Post-ack evidence cannot record $kind." }
        val durable = requireNotNull(durableJournal)
        val execution = requireNotNull(durableExecutions[entry.mutationId])
        require(
            execution.phase == StoredExecutionPhase.ACKED ||
                execution.phase == StoredExecutionPhase.EFFECTS_PENDING,
        ) { "Post-ack evidence requires ACKED or EFFECTS_PENDING, but was ${execution.phase}." }
        val occurredAt = wallClock.nowEpochMillis()
        val normalized =
            sanitizedMutationFailure(
                kind = kind,
                detail = detail,
                message = message,
                occurredAtEpochMillis = occurredAt,
            )
        durable.storage.transaction { transaction ->
            transaction.appendFailure(
                clientId = clientId,
                clientSequence = entry.durableClientSequence,
                generation = execution.currentGeneration,
                kind = normalized.kind,
                detail = normalized.detail,
                message = normalized.message,
                occurredAt = occurredAt,
            )
        }
        drainFailures += normalized
        val attempt = requireNotNull(durableAttempts[entry.mutationId])
        val publicState =
            when (execution.phase) {
                StoredExecutionPhase.ACKED -> MutationPendingState.ADOPTING
                StoredExecutionPhase.EFFECTS_PENDING ->
                    MutationPendingState.APPLYING_EFFECTS
                else -> error("Post-ack evidence published from ${execution.phase}.")
            }
        eventBus.tryEmit(
            MutationFailed(
                mutationId = entry.mutationId,
                identity = attempt.toEventIdentity(),
                occurredAtEpochMillis = occurredAt,
                generation = execution.currentGeneration,
                state = publicState,
                failure = normalized,
            ),
        )
    }

    /**
     * Atomically skips every pending effect, retires from REFRESH_REQUIRED, and advances
     * the contiguous local prefix. The durable commit and complete projection handoff are one
     * cancellation-safe operation; no acknowledgement, alias, tombstone, or invalidation runs.
     */
    private suspend fun retireDurableServerWins(
        identity: KeyIdentity,
        entry: JournalEntry<V>,
    ) {
        withContext(NonCancellable) {
            val commit =
                retirementPass.withLock {
                    val durable = requireNotNull(durableJournal)
                    val sequence = entry.durableClientSequence
                    val previous = requireNotNull(durableExecutions[entry.mutationId])
                    require(previous.phase == StoredExecutionPhase.REFRESH_REQUIRED) {
                        "ServerWins requires REFRESH_REQUIRED, but was ${previous.phase}."
                    }
                    val retiredAt = wallClock.nowEpochMillis()
                    val committed =
                        durable.storage.transaction { transaction ->
                            val skippedEffects = mutableListOf<StoredEffectRecord>()
                            val effects =
                                transaction
                                    .effects(clientId)
                                    .filter { effect -> effect.clientSequence == sequence }
                                    .sortedBy { effect -> effect.effectIndex }
                                    .map { effect ->
                                        if (
                                            effect.disposition ==
                                            MutationEffectDisposition.PENDING
                                        ) {
                                            effect.skippedAt(retiredAt).also { skipped ->
                                                transaction.advanceEffect(skipped)
                                                skippedEffects += skipped
                                            }
                                        } else {
                                            effect
                                        }
                                    }
                            val retired =
                                previous.copyExecution(
                                    phase = StoredExecutionPhase.RETIRED,
                                    retiredAt = retiredAt,
                                )
                            transaction.advanceExecution(retired)
                            val client = requireNotNull(transaction.client(clientId))
                            val gaps = retiredSequences.toMutableSet().apply { add(sequence) }
                            var prefix = client.retiredThroughSequence
                            while (gaps.remove(prefix + 1L)) prefix += 1L
                            transaction.advanceClient(
                                MutationClientRecord(
                                    recordVersion = client.recordVersion,
                                    clientId = client.clientId,
                                    lastAllocatedSequence = client.lastAllocatedSequence,
                                    retiredThroughSequence = prefix,
                                    serverConfirmedRetiredThroughSequence =
                                        client.serverConfirmedRetiredThroughSequence,
                                    createdAt = client.createdAt,
                                ),
                            )
                            DurableServerWinsCommit(
                                execution = retired,
                                effects = effects,
                                skippedEffects = skippedEffects,
                                retiredThroughSequence = prefix,
                                serverConfirmedRetiredThroughSequence =
                                    client.serverConfirmedRetiredThroughSequence,
                            )
                        }

                    // Durable release precedes runtime membership removal. The namespace process
                    // lease remains held by the enclosing pass until cleanup and signaling finish.
                    journal.retire(identity, entry.mutationId)
                    durableExecutions[entry.mutationId] = committed.execution
                    durableEffectRows[entry.mutationId] = committed.effects
                    retiredSequences += sequence
                    while (retiredSequences.remove(retiredThroughSequence + 1L)) {
                        retiredThroughSequence += 1L
                    }
                    check(retiredThroughSequence == committed.retiredThroughSequence)
                    serverConfirmedRetiredThroughSequence =
                        committed.serverConfirmedRetiredThroughSequence
                    phases.remove(entry.mutationId)
                    completedAttempts.remove(entry.mutationId)
                    committed
                }
            signalSink.emit(ProjectionRevisionKey(identity))
            val attempt = requireNotNull(durableAttempts[entry.mutationId])
            commit.skippedEffects.forEach { effect ->
                eventBus.tryEmit(
                    MutationEffectSkipped(
                        mutationId = entry.mutationId,
                        identity = attempt.toEventIdentity(),
                        occurredAtEpochMillis = checkNotNull(effect.completedAt),
                        generation = attempt.generation,
                        effectIndex = effect.effectIndex,
                    ),
                )
            }
            eventBus.tryEmit(
                MutationRetired(
                    mutationId = entry.mutationId,
                    identity = attempt.toEventIdentity(),
                    occurredAtEpochMillis = checkNotNull(commit.execution.retiredAt),
                    generation = commit.execution.currentGeneration,
                    retiredThroughSequence = commit.retiredThroughSequence,
                ),
            )
        }
    }

    private suspend fun finalizeDurableRetirement(
        key: K,
        entry: JournalEntry<V>,
    ): Boolean =
        withContext(NonCancellable) {
            val commit =
                retirementPass.withLock {
                    val commit =
                        persistDurableRetirementLocked(
                            entry = entry,
                            effectiveIdentity = key.identity(),
                            aliasActivation = null,
                        ) ?: return@withLock null
                    journal.retire(key.identity(), entry.mutationId)
                    publishDurableTombstoneReplacements(commit)
                    publishDurableRetirementAccounting(entry, commit)
                    phases.remove(entry.mutationId)
                    completedAttempts.remove(entry.mutationId)
                    commit
                }
            if (commit == null) {
                return@withContext false
            }
            signalSink.emit(key)
            publishRetiredEvent(entry, commit)
            true
        }

    private suspend fun finalizeDurableAliasRetirement(
        sourceKey: K,
        targetKey: K,
        entry: JournalEntry<V>,
    ): Boolean {
        val source = sourceKey.identity()
        val target = targetKey.identity()
        return withContext(NonCancellable) {
            val commit =
                retirementPass.withLock {
                    val activation = aliasRouter.activationRecord(source, wallClock.nowEpochMillis())
                    val commit =
                        persistDurableRetirementLocked(
                            entry = entry,
                            effectiveIdentity = target,
                            aliasActivation = activation,
                        ) ?: return@withLock null
                    val aliasPublication = requireNotNull(commit.aliasPublication)
                    mutations.withLock {
                        requireNotNull(durableJournal).publishAliasRetirement(
                            source = aliasPublication.source,
                            terminalTarget = aliasPublication.terminalTarget,
                            retiredMutationId = entry.mutationId,
                        )
                        publishDurableTombstoneReplacements(commit)
                        publishDurableRetirementAccounting(entry, commit)
                        cacheLiveKey(aliasPublication.terminalTarget, targetKey)
                        phases.remove(entry.mutationId)
                        completedAttempts.remove(entry.mutationId)
                        aliasRevisionSignal(aliasPublication.source).update { revision ->
                            revision + 1L
                        }
                        bumpResolutionPulse(aliasPublication.source)
                        bumpResolutionPulse(aliasPublication.terminalTarget)
                    }
                    commit
                }
            if (commit == null) return@withContext false
            signalSink.emit(sourceKey)
            signalSink.emit(targetKey)
            publishRetiredEvent(entry, commit)
            true
        }
    }

    /** Commits the adoption advance independently of retirement finalization. */
    private suspend fun persistDurableEffectsPending(entry: JournalEntry<V>) {
        val durable = requireNotNull(durableJournal)
        val previous = requireNotNull(durableExecutions[entry.mutationId])
        require(previous.phase == StoredExecutionPhase.ACKED) {
            "Durable adoption advance requires ACKED, but was ${previous.phase}."
        }
        val effectsPending = previous.copyExecution(phase = StoredExecutionPhase.EFFECTS_PENDING)
        retryablePostAckPersistence(entry) {
            durable.storage.transaction { transaction ->
                transaction.advanceExecution(effectsPending)
            }
        }
        durableExecutions[entry.mutationId] = effectsPending
        phases[entry.mutationId] = MutationExecutionPhase.EFFECTS_PENDING
        val attempt = requireNotNull(durableAttempts[entry.mutationId])
        val ack = requireNotNull(durableAcks[entry.mutationId])
        eventBus.tryEmit(
            MutationAdopted(
                mutationId = entry.mutationId,
                identity = attempt.toEventIdentity(),
                occurredAtEpochMillis = wallClock.nowEpochMillis(),
                generation = effectsPending.currentGeneration,
                presence = ack.authoritativePresence,
            ),
        )
    }

    private suspend fun persistDurableRetirementLocked(
        entry: JournalEntry<V>,
        effectiveIdentity: KeyIdentity,
        aliasActivation: MutationKeyAliasRecord?,
    ): DurableRetirementCommit? {
        val durable = requireNotNull(durableJournal)
        val sequence = entry.durableClientSequence
        val previous = requireNotNull(durableExecutions[entry.mutationId])
        require(previous.phase == StoredExecutionPhase.EFFECTS_PENDING) {
            "Durable retirement finalization requires EFFECTS_PENDING, but was ${previous.phase}."
        }
        val retiredAt = wallClock.nowEpochMillis()
        var commit: DurableRetirementCommit? = null
        retryablePostAckPersistence(entry) {
            durable.storage.transaction { transaction ->
                val effectsPending =
                    transaction.effects(clientId).any { effect ->
                        effect.clientSequence == sequence &&
                            effect.disposition ==
                            org.mobilenativefoundation.store6.mutations.storage.MutationEffectDisposition.PENDING
                    }
                if (effectsPending) return@transaction
                aliasActivation?.let(transaction::advanceAlias)
                val aliases = transaction.aliases()
                val tombstones = transaction.tombstones()
                val client = transaction.client(clientId)
                val (proposedCommit, advancedClient) =
                    nonRetryablePostAckInvariant {
                        val aliasesBySource = aliases.toAliasEdgesBySource()
                        val tombstoneActivation =
                            tombstones
                                .singleOrNull { tombstone ->
                                    tombstone.createdByClientId == clientId &&
                                        tombstone.createdBySequence == sequence &&
                                        tombstone.state == MutationTombstoneState.PENDING
                                }?.activatedAt(retiredAt)
                        val supersededTombstones =
                            tombstones
                                .filter { tombstone ->
                                    tombstone.state == MutationTombstoneState.ACTIVE &&
                                        terminalIdentity(
                                            tombstone.identity(),
                                            aliasesBySource,
                                        ) == effectiveIdentity
                                }
                                .map { tombstone ->
                                    tombstone.supersededBy(
                                        successorClientId = clientId,
                                        successorSequence = sequence,
                                        supersededAt = retiredAt,
                                    )
                                }
                        val currentClient = requireNotNull(client)
                        val gaps = retiredSequences.toMutableSet().apply { add(sequence) }
                        var prefix = currentClient.retiredThroughSequence
                        while (gaps.remove(prefix + 1L)) prefix += 1L
                        val retiredRecord =
                            previous.copyExecution(
                                phase = StoredExecutionPhase.RETIRED,
                                retiredAt = retiredAt,
                            )
                        DurableRetirementCommit(
                            execution = retiredRecord,
                            retiredThroughSequence = prefix,
                            serverConfirmedRetiredThroughSequence =
                                currentClient.serverConfirmedRetiredThroughSequence,
                            tombstoneReplacements =
                                supersededTombstones + listOfNotNull(tombstoneActivation),
                            aliasPublication =
                                aliasActivation?.let { committedAlias ->
                                    DurableAliasPublication(
                                        activation = committedAlias,
                                        terminalTarget = effectiveIdentity,
                                    )
                                },
                        ) to
                            MutationClientRecord(
                                recordVersion = currentClient.recordVersion,
                                clientId = currentClient.clientId,
                                lastAllocatedSequence = currentClient.lastAllocatedSequence,
                                retiredThroughSequence = prefix,
                                serverConfirmedRetiredThroughSequence =
                                    currentClient.serverConfirmedRetiredThroughSequence,
                                createdAt = currentClient.createdAt,
                            )
                    }
                proposedCommit.tombstoneReplacements.forEach(transaction::advanceTombstone)
                transaction.advanceExecution(proposedCommit.execution)
                transaction.advanceClient(advancedClient)
                commit = proposedCommit
            }
        }
        return commit
    }

    /** One cache replacement, only after routing/membership is coherent. */
    private fun publishDurableTombstoneReplacements(commit: DurableRetirementCommit) {
        if (commit.tombstoneReplacements.isNotEmpty()) {
            hydratedTombstones.replaceAllBy(
                replacements = commit.tombstoneReplacements,
                sameRecord = { current, replacement ->
                    current.sameGenerationAs(replacement)
                },
            )
        }
    }

    /** Process bookkeeping follows routing and tombstone publication. */
    private fun publishDurableRetirementAccounting(
        entry: JournalEntry<V>,
        commit: DurableRetirementCommit,
    ) {
        durableExecutions[entry.mutationId] = commit.execution
        retiredSequences += entry.durableClientSequence
        while (retiredSequences.remove(retiredThroughSequence + 1L)) {
            retiredThroughSequence += 1L
        }
        check(retiredThroughSequence == commit.retiredThroughSequence)
        serverConfirmedRetiredThroughSequence = commit.serverConfirmedRetiredThroughSequence
    }

    /** Publishes one completed retirement only after membership, routing, and signals cohere. */
    private fun publishRetiredEvent(
        entry: JournalEntry<V>,
        commit: DurableRetirementCommit,
    ) {
        val attempt = requireNotNull(durableAttempts[entry.mutationId])
        eventBus.tryEmit(
            MutationRetired(
                mutationId = entry.mutationId,
                identity = attempt.toEventIdentity(),
                occurredAtEpochMillis = checkNotNull(commit.execution.retiredAt),
                generation = commit.execution.currentGeneration,
                retiredThroughSequence = commit.retiredThroughSequence,
            ),
        )
    }

    /**
     * The lowest pending durable client sequence at [identity] within this pass's enqueue bound:
     * the pass drains the prefix that existed when it started and never chases intents enqueued
     * behind it. Ties (direct journal appends in module tests use the default sequence)
     * keep first-append order.
     */
    private suspend fun nextEligibleHead(
        identity: KeyIdentity,
        sequenceBound: Long,
        overrideBackoff: Boolean,
        initialEntry: JournalEntry<V>? = null,
    ): DrainHead<V>? =
        mutations.withLock {
            val owner = durableNamespaceOwners()[identity.namespace]
            if (owner != null && owner.identity != aliasRouter.terminalOf(identity)) {
                return@withLock null
            }
            val pending =
                replayableEntries(identity, journal.pendingSnapshot(identity))
                    .filter { entry -> entry.clientSequence <= sequenceBound }
            val hinted =
                initialEntry?.takeIf { candidate ->
                    pending.any { row -> row.mutationId == candidate.mutationId }
                }
            val entry =
                if (owner != null) {
                    pending.singleOrNull { row ->
                        row.durableClientSequence == owner.clientSequence
                    } ?: return@withLock null
                } else {
                    hinted ?: pending.minByOrNull { row -> row.clientSequence }
                    ?: return@withLock null
                }
            if (entry.mutationId in pendingEnqueuePublications) return@withLock null
            if (!overrideBackoff && hinted == null && !isBackoffEligible(entry)) {
                return@withLock null
            }
            DrainHead(
                entry = entry,
                phase = phases[entry.mutationId] ?: MutationExecutionPhase.UNPREPARED,
            )
        }

    /**
     * Eligibility derived only from durable facts at pass time. READY and REFRESH_REQUIRED
     * share the same schedule; every other phase is immediately eligible.
     * Jitter is uniform over the inclusive `[0, computed]` interval, drawn once by the caller's
     * entry selection for this pass, and never persisted.
     */
    private fun isBackoffEligible(entry: JournalEntry<V>): Boolean {
        val execution = durableExecutions[entry.mutationId] ?: return true
        if (
            execution.phase != StoredExecutionPhase.READY &&
            execution.phase != StoredExecutionPhase.REFRESH_REQUIRED
        ) {
            return true
        }
        if (execution.attempt == 0) return true
        val lastAttemptAt = checkNotNull(execution.lastAttemptAt) {
            "A retryable execution with completed attempts must retain lastAttemptAt."
        }
        val computed = backoffWindowMillis(execution.attempt)
        val jitter = backoffRandom.nextLong(computed + 1L)
        val eligibleAt =
            if (lastAttemptAt > Long.MAX_VALUE - jitter) Long.MAX_VALUE else lastAttemptAt + jitter
        return wallClock.nowEpochMillis() >= eligibleAt
    }

    private fun backoffWindowMillis(attempt: Int): Long {
        require(attempt >= 1)
        var computed = BACKOFF_BASE_MILLIS
        repeat(attempt - 1) {
            computed = minOf(BACKOFF_CAP_MILLIS, computed * 2L)
        }
        return computed
    }

    /**
     * The ordered base-capture loop. A present value accepts the first (pre-value) status
     * metadata, which may match or lag but cannot lead the value under Store's commit ordering.
     * On a missing value, status is read again and absence is accepted only when BOTH bracketing
     * statuses carry no metadata; otherwise a concurrent fetch-deletion or key/namespace/all-clear
     * window is open and the loop retries. The loop, not any single bracket, is the correctness
     * mechanism.
     */
    private suspend fun captureBase(key: K): CapturedBase<V> {
        while (true) {
            val leading = bookkeeper.status(key)
            val confirmed = baseReader(key)
            if (confirmed != null) {
                return CapturedBase(
                    presence = MutationPresence.Present(confirmed),
                    meta = snapshotMeta(leading?.meta),
                )
            }
            val trailing = bookkeeper.status(key)
            if (leading?.meta == null && trailing?.meta == null) {
                return CapturedBase(presence = MutationPresence.Absent, meta = null)
            }
        }
    }

    private fun project(
        base: MutationPresence<V>,
        entry: JournalEntry<V>,
    ): ProjectionOutcome<V> {
        if (entry.mutationId in codecBlockedMutationIds || entry.args === UnavailableMutationArgs) {
            return ProjectionOutcome(value = base, advanced = false, failure = null)
        }
        val registration =
            registry.registrations[entry.mutatorId]
                ?: return ProjectionOutcome(value = base, advanced = false, failure = null)
        return try {
            val result = registration.project(base, entry.args)
            if (result == null) {
                // A null projector result means decline only — passthrough in projection,
                // halt of the same-key suffix in drain, never a deletion.
                ProjectionOutcome(value = base, advanced = false, failure = null)
            } else {
                ProjectionOutcome(value = result, advanced = true, failure = null)
            }
        } catch (failure: Throwable) {
            // Containment swallows everything including CancellationException: a rethrow out of
            // Overlay.apply terminalizes the key's projected streams permanently.
            poisonSink.tryEmit(
                PoisonedIntent(
                    mutationId = entry.mutationId,
                    mutatorId = entry.mutatorId,
                    failure = failure,
                ),
            )
            ProjectionOutcome(value = base, advanced = false, failure = failure)
        }
    }

    /**
     * Builds the immutable in-memory attempt generation: stable client identity and
     * sequence, the advertised contiguous retired prefix, generation-scoped deterministic
     * idempotency key, the retained value-codec version, and base/mine rebuilt through the
     * codec's defensive-copy boundaries so a mutating server cannot alter reconstruction.
     */
    private fun buildPush(
        key: K,
        entry: JournalEntry<V>,
        base: MutationPresence<V>,
        mine: MutationPresence<V>,
        baseMeta: StoreMeta?,
    ): MutationPush<K, V> =
        MutationPush(
            identity = MutationKeyIdentity(key.namespace.value, key.canonicalId()),
            key = key,
            clientId = clientId,
            clientSequence = entry.clientSequence,
            retiredThroughSequence = retiredThroughSequence,
            mutationId = entry.mutationId,
            generation = IN_MEMORY_GENERATION,
            idempotencyKey = "$clientId:${entry.clientSequence}:g$IN_MEMORY_GENERATION",
            valueCodecVersion = valueCodecVersion,
            base = copiedPresence(base),
            mine = copiedPresence(mine),
            baseMeta = baseMeta,
        )

    /**
     * The in-memory present adoption: validate the acknowledgement and its optional canonical
     * target, then `apply -> confirmFresh` at the EFFECTIVE canonical key, then retire — and for a
     * redirect, activate the alias inside the same `NonCancellable` accepted-state handoff that
     * advances the mutation-owned alias revision.
     *
     * The authoritative value is rebuilt through the codec's copy boundaries before adoption so a
     * server retaining its acknowledged object cannot mutate adopted state or the echo-forward
     * base. Adoption failure propagates; the intent stays pending. An alias-protocol violation
     * (cross-namespace target, retarget, cycle, or a generation retry acknowledging a different
     * canonical target) records one normalized `PROTOCOL` carrier, returns the intent to `READY`,
     * performs no adoption, and returns null so the pass halts.
     */
    private suspend fun stageLegacyPresentAck(
        key: K,
        entry: JournalEntry<V>,
        idempotencyKey: String,
        ack: MutationPresentAck<K, V>,
    ): LegacyPendingPresentAck<K, V>? =
        mutations.withLock {
            val source = key.identity()
            val admission =
                aliasRouter.admit(
                    source = source,
                    claimed = ack.canonicalKey?.identity(),
                    idempotencyKey = idempotencyKey,
                    createdByClientId = clientId,
                    createdBySequence = entry.durableClientSequence,
                    createdAt = wallClock.nowEpochMillis(),
                )
            when (admission) {
                is AliasAdmission.Rejected -> {
                    recordNormalizedFailure(
                        kind = MutationFailureKind.PROTOCOL,
                        detail = admission.detail,
                        message = admission.message,
                    )
                    completedAttempts[entry.mutationId] =
                        (completedAttempts[entry.mutationId] ?: 0) + 1
                    phases[entry.mutationId] = MutationExecutionPhase.READY
                    null
                }
                is AliasAdmission.Admitted -> {
                    aliasRouter.commitAdmission(admission)
                    val targetKey = admission.redirect?.let { checkNotNull(ack.canonicalKey) } ?: key
                    cacheLiveKey(targetKey.identity(), targetKey)
                    val pending =
                        LegacyPendingPresentAck(
                            sourceKey = key,
                            targetKey = targetKey,
                            authoritative = copiedValue(ack.authoritative),
                            etag = ack.etag,
                        )
                    phases[entry.mutationId] = MutationExecutionPhase.ACKED
                    legacyPendingPresentAcks[entry.mutationId] = pending
                    pending
                }
            }
        }

    private suspend fun resumeLegacyPresentAck(
        entry: JournalEntry<V>,
        pending: LegacyPendingPresentAck<K, V>,
    ): PresentAdoption<K, V> {
        try {
            handle.apply(pending.targetKey, pending.authoritative)
            handle.confirmFresh(pending.targetKey, pending.etag)
            if (pending.sourceKey.identity() == pending.targetKey.identity()) {
                retire(pending.sourceKey, entry)
            } else {
                retireAndActivateAlias(
                    sourceKey = pending.sourceKey,
                    targetKey = pending.targetKey,
                    entry = entry,
                )
            }
        } catch (failure: Throwable) {
            // The codec-less path retries by retransmitting the same generation. Retain
            // the alias receipt, but discard only this in-process adoption cursor so the next
            // foreground pass reaches the server and revalidates the acknowledged target.
            legacyPendingPresentAcks.remove(entry.mutationId)
            throw failure
        }
        legacyPendingPresentAcks.remove(entry.mutationId)
        return PresentAdoption(
            adopted = pending.authoritative,
            effectiveKey = pending.targetKey,
        )
    }

    /**
     * The absent adoption: the acknowledged state is recorded, confirmed absence is adopted
     * through the bound `Store.clear` door, and only then does the intent retire and signal.
     * The sealed [MutationAbsentAck] carries no canonical key, so rekey-on-deletion is
     * unrepresentable.
     */
    private suspend fun adoptAbsent(
        key: K,
        entry: JournalEntry<V>,
    ) {
        phases[entry.mutationId] = MutationExecutionPhase.ACKED
        absentAdoption(key)
        retire(key, entry)
    }

    private suspend fun retire(
        key: K,
        entry: JournalEntry<V>,
    ) {
        journal.retire(key.identity(), entry.mutationId)
        phases.remove(entry.mutationId)
        completedAttempts.remove(entry.mutationId)
        recordRetiredSequence(entry.clientSequence)
        signalChange(key)
    }

    /**
     * One accepted-state handoff: inside a single `NonCancellable` block the intent retires, the
     * redirect activates, the source's queued siblings re-home to the canonical identity (merged
     * by durable client sequence — selection is sequence-ordered, so physical order is not
     * authority), the canonical `K` is cached, and the mutation-owned alias revision and
     * resolution pulses advance synchronously BEFORE the compatibility `Overlay.changes` signals
     * are emitted. Caller cancellation after the in-memory commit therefore cannot strand a live
     * provisional stream.
     */
    private suspend fun retireAndActivateAlias(
        sourceKey: K,
        targetKey: K,
        entry: JournalEntry<V>,
    ) {
        val source = sourceKey.identity()
        val target = targetKey.identity()
        withContext(NonCancellable) {
            mutations.withLock {
                val activation = aliasRouter.activationRecord(source, wallClock.nowEpochMillis())
                activation?.let { record -> aliasRouter.persistActivation(record) }
                val cache = journal as? StorageBackedMutationJournal<V>
                if (cache == null) {
                    journal.retire(source, entry.mutationId)
                    aliasRouter.publishActivation(source)
                    for (sibling in orderedPending(source)) {
                        journal.rehome(source, target, sibling)
                    }
                } else {
                    cache.publishAliasRetirement(
                        source = source,
                        terminalTarget = target,
                        retiredMutationId = entry.mutationId,
                    )
                }
                cacheLiveKey(target, targetKey)
                phases.remove(entry.mutationId)
                completedAttempts.remove(entry.mutationId)
                recordRetiredSequence(entry.clientSequence)
                // The synchronous stateful revision handoff: no cancellable suspension may
                // separate the in-memory commit above from these advances.
                aliasRevisionSignal(source).update { revision -> revision + 1L }
                bumpResolutionPulse(source)
                bumpResolutionPulse(target)
            }
            signalSink.emit(sourceKey)
            signalSink.emit(targetKey)
        }
    }

    /** Advances the in-memory contiguous retired prefix; gaps hold the high-water. */
    private suspend fun recordRetiredSequence(clientSequence: Long) {
        retirementPass.withLock {
            if (clientSequence <= retiredThroughSequence) return@withLock
            retiredSequences += clientSequence
            while (retiredSequences.remove(retiredThroughSequence + 1)) {
                retiredThroughSequence += 1
            }
        }
    }

    /**
     * Captures the intent's normalized invalidation-effect snapshot before its first push. A
     * throwing `stales` function is contained exactly like a throwing projector — ephemeral
     * poison, no transport — and halts this key's pass.
     */
    private fun captureEffects(
        key: K,
        entry: JournalEntry<V>,
    ): Boolean {
        val effects = evaluateEffects(key, entry) ?: return false
        effectSnapshots[entry.mutationId] = effects
        return true
    }

    private fun evaluateEffects(
        key: K,
        entry: JournalEntry<V>,
    ): List<MutationEffectRecord>? {
        val registration = registry.registrations[entry.mutatorId] ?: return null
        return try {
            normalizedMutationEffects(registration.stales(key, entry.args))
        } catch (failure: Throwable) {
            poisonSink.tryEmit(
                PoisonedIntent(
                    mutationId = entry.mutationId,
                    mutatorId = entry.mutatorId,
                    failure = failure,
                ),
            )
            null
        }
    }

    /** The captured in-memory effect snapshot; the durable rows are [durableEffectsSnapshot]. */
    internal fun capturedEffectsSnapshot(mutationId: String): List<MutationEffectRecord>? =
        effectSnapshots[mutationId]

    internal fun durableEffectsSnapshot(mutationId: String): List<StoredEffectRecord> =
        durableEffectRows[mutationId].orEmpty()

    internal fun tombstoneSnapshot(identity: KeyIdentity): List<MutationKeyTombstoneRecord> =
        hydratedTombstones.filter { row ->
            row.namespace == identity.namespace && row.canonicalId == identity.canonicalId
        }

    private fun copiedPresence(presence: MutationPresence<V>): MutationPresence<V> =
        when (presence) {
            is MutationPresence.Present -> MutationPresence.Present(copiedValue(presence.value))
            MutationPresence.Absent -> MutationPresence.Absent
        }

    /**
     * The library-side defensive-copy boundary for values crossing the transport seam: encode
     * then decode through the retained codec so the consumer-visible object is never the
     * engine's stored/projected instance. Direct engine constructions without a codec pass the
     * value through — the factory always retains one.
     */
    private fun copiedValue(value: V): V {
        val codec = valueCodec ?: return value
        return codec.decodeCopied(valueCodecVersion, codec.encodeCopied(value))
    }

    /** A library-owned immutable snapshot of captured metadata fields. */
    private fun snapshotMeta(meta: StoreMeta?): StoreMeta? =
        meta?.let { captured ->
            CapturedMetaSnapshot(
                writtenAtEpochMillis = captured.writtenAtEpochMillis,
                etag = captured.etag,
            )
        }

    private fun presenceOf(value: V?): MutationPresence<V> =
        if (value == null) MutationPresence.Absent else MutationPresence.Present(value)

    private suspend fun signalChange(key: K) {
        // Once a journal transition completes, caller cancellation cannot discard its accepted
        // key-change handoff and strand a different key behind SharedFlow replay backpressure.
        withContext(NonCancellable) {
            signalSink.emit(key)
        }
    }
}

private class CapturedBase<V : Any>(
    val presence: MutationPresence<V>,
    val meta: StoreMeta?,
)

private class CapturedMetaSnapshot(
    override val writtenAtEpochMillis: Long,
    override val etag: String?,
) : StoreMeta

private class ProjectionOutcome<V : Any>(
    val value: MutationPresence<V>,
    val advanced: Boolean,
    val failure: Throwable?,
)

private enum class DurablePreparationOutcome {
    PREPARED,
    BLOCKED,
    PARKED,
}

private sealed interface PreconditionSelection {
    class Selected(
        val meta: StoreMeta?,
    ) : PreconditionSelection

    class Failed(
        val failure: Throwable,
    ) : PreconditionSelection
}

private enum class DurableConflictOutcome {
    RETRY_PREPARED,
    PARKED,
    RETIRED,
}

private enum class DurableConflictReceiptOutcome {
    REFRESH_REQUIRED,
    PARKED,
}

private enum class DurableAckReceiptOutcome {
    ACKED,
    PARKED,
    HALTED,
}

private sealed interface DurableAckReceiptDecision {
    val idempotencyKey: String
    val pinnedTarget: KeyIdentity?

    class Commit(
        val aliasAdmission: AliasAdmission.Admitted?,
        override val idempotencyKey: String,
        override val pinnedTarget: KeyIdentity,
    ) : DurableAckReceiptDecision

    class Reject(
        val source: KeyIdentity,
        val rejection: AliasAdmission.Rejected,
        override val idempotencyKey: String,
        override val pinnedTarget: KeyIdentity?,
    ) : DurableAckReceiptDecision
}

private class DurableConflictCommit(
    val attempt: MutationAttemptRecord,
    val execution: MutationExecutionRecord,
    val failure: MutationFailureRecord?,
)

private class DurableServerWinsCommit(
    val execution: MutationExecutionRecord,
    val effects: List<StoredEffectRecord>,
    val skippedEffects: List<StoredEffectRecord>,
    val retiredThroughSequence: Long,
    val serverConfirmedRetiredThroughSequence: Long,
)

/** Private postcommit carrier keeping durable truth ahead of every runtime publication. */
private class DurableRetirementCommit(
    val execution: MutationExecutionRecord,
    val retiredThroughSequence: Long,
    val serverConfirmedRetiredThroughSequence: Long,
    val tombstoneReplacements: List<MutationKeyTombstoneRecord>,
    val aliasPublication: DurableAliasPublication?,
)

private class DurableAliasPublication(
    val activation: MutationKeyAliasRecord,
    val terminalTarget: KeyIdentity,
) {
    val source: KeyIdentity =
        KeyIdentity(
            namespace = activation.sourceNamespace,
            canonicalId = activation.sourceCanonicalId,
        )
}

private class PreAckParkCandidate(
    val kind: MutationFailureKind,
    val detail: String,
    val message: String,
)

private class DurableParkCommit(
    val failure: MutationFailureRecord,
    val execution: MutationExecutionRecord,
)

/** Signal-only key: core matches Overlay.changes solely by namespace/canonical identity. */
private class ProjectionRevisionKey(
    identity: KeyIdentity,
) : StoreKey {
    override val namespace: StoreNamespace = StoreNamespace(identity.namespace)
    private val canonicalId: String = identity.canonicalId

    override fun canonicalId(): String = canonicalId
}

private object UnavailableMutationArgs

private fun MutationFailureRecord.toPublicFailure(): MutationFailure =
    MutationFailure(
        kind = kind,
        detail = detail,
        message = message,
        occurredAtEpochMillis = occurredAt,
    )

private fun StoredExecutionPhase.toEnginePhase(): MutationExecutionPhase =
    when (this) {
        StoredExecutionPhase.UNPREPARED -> MutationExecutionPhase.UNPREPARED
        StoredExecutionPhase.READY -> MutationExecutionPhase.READY
        StoredExecutionPhase.INFLIGHT -> MutationExecutionPhase.INFLIGHT
        StoredExecutionPhase.REFRESH_REQUIRED -> MutationExecutionPhase.REFRESH_REQUIRED
        StoredExecutionPhase.ACKED -> MutationExecutionPhase.ACKED
        StoredExecutionPhase.EFFECTS_PENDING -> MutationExecutionPhase.EFFECTS_PENDING
        StoredExecutionPhase.PARKED -> MutationExecutionPhase.PARKED
        StoredExecutionPhase.RETIRED -> MutationExecutionPhase.RETIRED
    }

/** C8's complete derived durable owner predicate; no separate owner row is persisted. */
private fun MutationExecutionRecord.ownsNamespaceAuthority(): Boolean =
    when (phase) {
        StoredExecutionPhase.INFLIGHT,
        StoredExecutionPhase.REFRESH_REQUIRED,
        StoredExecutionPhase.ACKED,
        StoredExecutionPhase.EFFECTS_PENDING,
        -> true

        StoredExecutionPhase.READY -> attempt > 0 || currentGeneration > 1

        StoredExecutionPhase.UNPREPARED,
        StoredExecutionPhase.PARKED,
        StoredExecutionPhase.RETIRED,
        -> false
    }

private fun MutationExecutionPhase.permitsPreAckParking(kind: MutationFailureKind): Boolean =
    when (this) {
        MutationExecutionPhase.UNPREPARED,
        MutationExecutionPhase.READY,
        ->
            kind == MutationFailureKind.IDENTITY ||
                kind == MutationFailureKind.CODEC ||
                kind == MutationFailureKind.PROJECTION

        MutationExecutionPhase.INFLIGHT,
        MutationExecutionPhase.REFRESH_REQUIRED,
        -> kind == MutationFailureKind.IDENTITY || kind == MutationFailureKind.CODEC

        MutationExecutionPhase.ACKED,
        MutationExecutionPhase.EFFECTS_PENDING,
        MutationExecutionPhase.PARKED,
        MutationExecutionPhase.RETIRED,
        -> false
    }

private fun MutationExecutionRecord.copyExecution(
    phase: StoredExecutionPhase = this.phase,
    currentGeneration: Int = this.currentGeneration,
    attempt: Int = this.attempt,
    lastAttemptAt: Long? = this.lastAttemptAt,
    activeFailureId: Long? = this.activeFailureId,
    retiredAt: Long? = this.retiredAt,
): MutationExecutionRecord =
    MutationExecutionRecord(
        clientId = clientId,
        clientSequence = clientSequence,
        phase = phase,
        currentGeneration = currentGeneration,
        attempt = attempt,
        lastAttemptAt = lastAttemptAt,
        activeFailureId = activeFailureId,
        retiredAt = retiredAt,
    )

private fun MutationAttemptRecord.withConflictReceipt(
    conflictMetaPresent: Boolean,
    conflictWrittenAt: Long?,
    conflictEtag: String?,
    conflictReceivedAt: Long,
): MutationAttemptRecord =
    MutationAttemptRecord(
        clientId = clientId,
        clientSequence = clientSequence,
        generation = generation,
        effectiveNamespace = effectiveNamespace,
        effectiveCanonicalId = effectiveCanonicalId,
        valueCodecVersion = valueCodecVersion,
        basePresence = basePresence,
        baseBlob = baseBlob,
        minePresence = minePresence,
        mineBlob = mineBlob,
        preconditionMetaPresent = preconditionMetaPresent,
        preconditionWrittenAt = preconditionWrittenAt,
        preconditionEtag = preconditionEtag,
        advertisedRetiredThroughSequence = advertisedRetiredThroughSequence,
        generationIdempotencyKey = generationIdempotencyKey,
        preparedAt = preparedAt,
        conflictMetaPresent = conflictMetaPresent,
        conflictWrittenAt = conflictWrittenAt,
        conflictEtag = conflictEtag,
        conflictReceivedAt = conflictReceivedAt,
    )

private fun StoredEffectRecord.skippedAt(completedAt: Long): StoredEffectRecord =
    StoredEffectRecord(
        clientId = clientId,
        clientSequence = clientSequence,
        effectIndex = effectIndex,
        kind = kind,
        namespace = namespace,
        canonicalId = canonicalId,
        createdAt = createdAt,
        disposition = MutationEffectDisposition.SKIPPED,
        completedAt = completedAt,
    )

private fun StoredEffectRecord.appliedAt(completedAt: Long): StoredEffectRecord =
    StoredEffectRecord(
        clientId = clientId,
        clientSequence = clientSequence,
        effectIndex = effectIndex,
        kind = kind,
        namespace = namespace,
        canonicalId = canonicalId,
        createdAt = createdAt,
        disposition = MutationEffectDisposition.APPLIED,
        completedAt = completedAt,
    )

private fun MutationKeyTombstoneRecord.activatedAt(
    activatedAt: Long,
): MutationKeyTombstoneRecord =
    MutationKeyTombstoneRecord(
        namespace = namespace,
        canonicalId = canonicalId,
        createdByClientId = createdByClientId,
        createdBySequence = createdBySequence,
        state = MutationTombstoneState.ACTIVE,
        createdAt = createdAt,
        activatedAt = activatedAt,
        supersededByClientId = null,
        supersededBySequence = null,
        supersededAt = null,
    )

private fun MutationKeyTombstoneRecord.supersededBy(
    successorClientId: String,
    successorSequence: Long,
    supersededAt: Long,
): MutationKeyTombstoneRecord =
    MutationKeyTombstoneRecord(
        namespace = namespace,
        canonicalId = canonicalId,
        createdByClientId = createdByClientId,
        createdBySequence = createdBySequence,
        state = MutationTombstoneState.SUPERSEDED,
        createdAt = createdAt,
        activatedAt = checkNotNull(activatedAt),
        supersededByClientId = successorClientId,
        supersededBySequence = successorSequence,
        supersededAt = supersededAt,
    )

private fun MutationKeyTombstoneRecord.identity(): KeyIdentity =
    KeyIdentity(namespace, canonicalId)

private fun MutationKeyTombstoneRecord.sameGenerationAs(
    other: MutationKeyTombstoneRecord,
): Boolean =
    namespace == other.namespace &&
        canonicalId == other.canonicalId &&
        createdByClientId == other.createdByClientId &&
        createdBySequence == other.createdBySequence

private fun <V : Any> MutationPresence<V>.toPresenceState(): MutationPresenceState =
    when (this) {
        is MutationPresence.Present -> MutationPresenceState.PRESENT
        MutationPresence.Absent -> MutationPresenceState.ABSENT
    }

private fun <V : Any> MutationPresence<V>.encodePresentOrNull(
    codec: MutationCodec<V>,
): ByteArray? =
    when (this) {
        is MutationPresence.Present -> codec.encodeCopied(value)
        MutationPresence.Absent -> null
    }

private fun <V : Any> MutationAttemptRecord.decodeBase(
    codec: MutationCodec<V>,
): MutationPresence<V> =
    when (basePresence) {
        MutationPresenceState.PRESENT ->
            MutationPresence.Present(codec.decodeCopied(valueCodecVersion, checkNotNull(baseBlob)))
        MutationPresenceState.ABSENT -> MutationPresence.Absent
    }

private fun <V : Any> MutationAttemptRecord.decodeMine(
    codec: MutationCodec<V>,
): MutationPresence<V> =
    when (minePresence) {
        MutationPresenceState.PRESENT ->
            MutationPresence.Present(codec.decodeCopied(valueCodecVersion, checkNotNull(mineBlob)))
        MutationPresenceState.ABSENT -> MutationPresence.Absent
    }

private fun MutationAttemptRecord.preconditionMetaOrNull(): StoreMeta? =
    if (preconditionMetaPresent) {
        CapturedMetaSnapshot(
            writtenAtEpochMillis = checkNotNull(preconditionWrittenAt),
            etag = preconditionEtag,
        )
    } else {
        null
    }

private fun MutationAttemptRecord.conflictMetaOrNull(): StoreMeta? =
    if (conflictMetaPresent == true) {
        CapturedMetaSnapshot(
            writtenAtEpochMillis = checkNotNull(conflictWrittenAt),
            etag = conflictEtag,
        )
    } else {
        null
    }

private fun KeyIdentity.toEventIdentity(): MutationKeyIdentity =
    MutationKeyIdentity(namespace, canonicalId)

private fun MutationAttemptRecord.toEventIdentity(): MutationKeyIdentity =
    MutationKeyIdentity(effectiveNamespace, effectiveCanonicalId)

/** One completed present adoption: the adopted value and the effective (possibly rekeyed) key. */
private class PresentAdoption<K : StoreKey, V : Any>(
    val adopted: V,
    val effectiveKey: K,
)

/** Copy-on-write map used by cross-identity drain bookkeeping in common code. */
private class AtomicMutableMap<K, V> {
    private val state = MutableStateFlow<Map<K, V>>(emptyMap())

    operator fun get(key: K): V? = state.value[key]

    operator fun set(
        key: K,
        value: V,
    ) {
        state.update { current -> current + (key to value) }
    }

    fun remove(key: K): V? {
        val previous = state.value[key]
        state.update { current -> current - key }
        return previous
    }

    fun clear() {
        state.value = emptyMap()
    }

    val values: Collection<V>
        get() = state.value.values
}

/** Copy-on-write list used by cross-identity drain bookkeeping in common code. */
private class AtomicMutableList<T> {
    private val state = MutableStateFlow<List<T>>(emptyList())

    operator fun plusAssign(value: T) {
        state.update { current -> current + value }
    }

    fun addAll(values: Iterable<T>) {
        state.update { current -> current + values }
    }

    fun clear() {
        state.value = emptyList()
    }

    fun replaceAllBy(
        replacements: List<T>,
        sameRecord: (T, T) -> Boolean,
    ) {
        state.update { current ->
            current.filterNot { existing ->
                replacements.any { replacement -> sameRecord(existing, replacement) }
            } + replacements
        }
    }

    fun toList(): List<T> = state.value

    fun filter(predicate: (T) -> Boolean): List<T> = state.value.filter(predicate)
}

/** Copy-on-write set used by cross-identity drain bookkeeping in common code. */
private class AtomicMutableSet<T> {
    private val state = MutableStateFlow<Set<T>>(emptySet())

    operator fun contains(value: T): Boolean = value in state.value

    operator fun plusAssign(value: T) {
        state.update { current -> current + value }
    }

    fun remove(value: T): Boolean {
        val contained = value in state.value
        state.update { current -> current - value }
        return contained
    }

    fun clear() {
        state.value = emptySet()
    }

    fun toMutableSet(): MutableSet<T> = state.value.toMutableSet()
}

private data class RetirementState(
    val retiredThroughSequence: Long = 0L,
    val serverConfirmedRetiredThroughSequence: Long = 0L,
)

private class LegacyPendingPresentAck<K : StoreKey, V : Any>(
    val sourceKey: K,
    val targetKey: K,
    val authoritative: V,
    val etag: String?,
)

private class DrainRehome<K : StoreKey, V : Any>(
    val processingKey: K,
    val targetIdentity: KeyIdentity,
    val entry: JournalEntry<V>,
    val sequenceBound: Long,
)

private class DrainHead<V : Any>(
    val entry: JournalEntry<V>,
    val phase: MutationExecutionPhase,
)

/** Private control-flow marker; keyed callers always receive [exposed] verbatim. */
private class RetryablePostAckFailure(
    val exposed: Throwable,
    val mutationId: String,
    val retainedPhase: StoredExecutionPhase,
) : RuntimeException(exposed.message, exposed)

/** Rolls back a storage callback while preserving a raw durability/programming invariant. */
private class NonRetryablePostAckInvariantFailure(
    val exposed: Throwable,
) : RuntimeException(exposed.message, exposed)

private class DurableNamespaceOwner(
    val clientSequence: Long,
    val identity: KeyIdentity,
)

/**
 * One identity-aware, ref-counted process lease per namespace for this engine's fixed client id.
 * Equal-effective contenders reserve and wait; distinct contenders return immediately. Durable
 * rows decide authority, and cleanup remains cancellation-safe through the complete pass handoff.
 */
private class NamespaceDrainScheduler {
    private val guard = Mutex()
    private val slots = mutableMapOf<String, NamespaceDrainSlot>()

    suspend fun tryWithNamespace(
        requestedIdentity: KeyIdentity,
        sameEffectiveIdentity: (KeyIdentity, KeyIdentity) -> Boolean,
        block: suspend () -> Unit,
    ): Boolean {
        val namespace = requestedIdentity.namespace
        var acquired = false
        val slot =
            guard.withLock {
                val existing = slots[namespace]
                when {
                    existing == null -> {
                        NamespaceDrainSlot(requestedIdentity).also { created ->
                            slots[namespace] = created
                            acquired = true
                        }
                    }

                    sameEffectiveIdentity(existing.leasedIdentity, requestedIdentity) -> {
                        existing.users += 1
                        existing
                    }

                    else -> null
                }
            } ?: return false
        try {
            if (!acquired) {
                slot.mutex.lock()
                acquired = true
            }
            block()
            return true
        } finally {
            withContext(NonCancellable) {
                if (acquired) {
                    slot.mutex.unlock()
                }
                guard.withLock {
                    check(slot.users > 0)
                    slot.users -= 1
                    if (slot.users == 0) {
                        check(slots[namespace] === slot)
                        slots.remove(namespace)
                    }
                }
            }
        }
    }
}

private class NamespaceDrainSlot(
    val leasedIdentity: KeyIdentity,
    val mutex: Mutex = Mutex(locked = true),
    var users: Int = 1,
)

/**
 * Ref-counted per-effective-identity mutex registry. A slot remains reachable while either a
 * holder or waiter references it and is removed immediately after its final user exits.
 */
private class IdentityDrainScheduler {
    private val guard = Mutex()
    private val slots = mutableMapOf<KeyIdentity, IdentityDrainSlot>()

    suspend fun <T> withIdentity(
        identity: KeyIdentity,
        block: suspend () -> T,
    ): T = withIdentities(listOf(identity), block)

    suspend fun <T> withIdentities(
        identities: Collection<KeyIdentity>,
        block: suspend () -> T,
    ): T {
        val orderedIdentities =
            identities
                .distinct()
                .sortedWith(compareBy<KeyIdentity>({ it.namespace }, { it.canonicalId }))
        require(orderedIdentities.isNotEmpty())
        val reserved =
            guard.withLock {
                orderedIdentities.map { identity ->
                    identity to
                        slots.getOrPut(identity) { IdentityDrainSlot() }.also { current ->
                            current.users += 1
                        }
                }
            }
        val acquired = mutableListOf<Pair<KeyIdentity, IdentityDrainSlot>>()
        try {
            for (reservation in reserved) {
                reservation.second.mutex.lock()
                acquired += reservation
            }
            return block()
        } finally {
            withContext(NonCancellable) {
                for ((_, slot) in acquired.asReversed()) {
                    slot.mutex.unlock()
                }
                guard.withLock {
                    for ((identity, slot) in reserved) {
                        slot.users -= 1
                        check(slot.users >= 0)
                        if (slot.users == 0) {
                            check(slots[identity] === slot)
                            slots.remove(identity)
                        }
                    }
                }
            }
        }
    }
}

private class IdentityDrainSlot(
    val mutex: Mutex = Mutex(),
    var users: Int = 0,
)
