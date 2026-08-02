@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

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
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
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

private const val HYDRATION_FAILURE_DETAIL_VALUE_PRE_ACK: String = "value-codec-pre-ack"
private const val HYDRATION_FAILURE_DETAIL_VALUE_ACKED: String = "value-codec-acked"
private const val HYDRATION_FAILURE_DETAIL_MUTATOR_MISSING: String = "mutator-missing"
private const val HYDRATION_FAILURE_DETAIL_ARGS: String = "args-codec"

/** The single in-memory attempt generation every 021 push transmits; merges are 023's (D2). */
private const val IN_MEMORY_GENERATION: Int = 1

/** Stable machine detail for a resolver that returned null during global drain (D14). */
internal const val DRAIN_FAILURE_DETAIL_RESOLVER_NULL: String = "resolver-null"

/** Stable machine detail for a resolver whose returned pair mismatched the request (D14). */
internal const val DRAIN_FAILURE_DETAIL_IDENTITY_MISMATCH: String = "resolver-identity-mismatch"

/** Stable machine detail for a resolver that threw a non-cancellation failure (D14). */
internal const val DRAIN_FAILURE_DETAIL_RESOLVER_THROW: String = "resolver-throw"

/** Stable machine detail for a keyed drain whose aliased terminal key failed to resolve (D14). */
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
 * The outcome of one terminal-identity resolution attempt for a facade entry point (D14/D15a).
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
        val message: String,
        val cause: Throwable?,
    ) : TerminalKeyResolution<Nothing>
}

internal class MutationEngine<K : StoreKey, V : Any>(
    private val registry: MutatorRegistry<K, V>,
    private val server: MutationServer<K, V>,
    private val journal: MutationJournal<V> = InMemoryMutationJournal(),
    // D9: the exact Bookkeeper/SourceOfTruth instances installed in the delegated Store are
    // retained here — ordered base capture reads [bookkeeper]; 024's transactional selection
    // consumes both.
    internal val bookkeeper: Bookkeeper = MutationBookkeeper(),
    internal val sourceOfTruth: SourceOfTruth<K, V> = MutationSourceOfTruth(),
    // D14/D7/D2: retained factory inputs. The resolver is the global-drain correctness path;
    // the value codec/version isolate every push and adoption behind defensive blob copies;
    // conflicts is stored for 023. Defaults exist only for direct engine construction in
    // module tests.
    internal val keyResolver: MutationKeyResolver<K> = MutationKeyResolver { null },
    internal val valueCodecVersion: Int = 1,
    internal val valueCodec: MutationCodec<V>? = null,
    internal val conflicts: MutationConflictRegistration<K, V>? = null,
    // T4.4/T4.5 wiring: the factory binds the delegated Store's LocalOnly read and clear door
    // through these lambdas after the delegate exists; direct engine tests bind their own.
    private val baseReader: suspend (K) -> V? = { null },
    private val absentAdoption: suspend (K) -> Unit = {},
    // Stamps journal enqueue times and normalized failure times. The factory threads the
    // builder-retained clock or the mutations-owned system default.
    private val wallClock: WallClock = MutationsSystemWallClock,
    // R-0 §1's stable installation identity, in-memory form: one fixed string per engine is the
    // ruled 021 shape; 022 owns durable client rows.
    internal val clientId: String = "client-0",
) {
    private val mutations = Mutex()
    private val hydration = Mutex()
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

    // In-memory effect snapshots captured before first push (D8/R-0 §7); never executed at 021.
    private val effectSnapshots = mutableMapOf<String, List<MutationEffectRecord>>()
    private val durableEffectRows = mutableMapOf<String, List<StoredEffectRecord>>()
    private val hydratedTombstones = mutableListOf<MutationKeyTombstoneRecord>()

    // In-memory execution bookkeeping for truthful inspection (D3/R-0 §3). All of it is
    // rewritten over durable records at 022/023.
    private val phases = mutableMapOf<String, MutationExecutionPhase>()
    private val completedAttempts = mutableMapOf<String, Int>()
    private val drainFailures = mutableListOf<MutationFailure>()
    private val durableExecutions = mutableMapOf<String, MutationExecutionRecord>()
    private val durableAttempts = mutableMapOf<String, MutationAttemptRecord>()
    private val durableAcks = mutableMapOf<String, MutationAckRecord>()
    private val deadLettersByMutationId = mutableMapOf<String, DeadLetter>()
    private val codecBlockedMutationIds = mutableSetOf<String>()

    // D12/D14: the live key map is CACHE ONLY. Global drain's correctness path is the durable
    // journal identity plus the resolver; every cache hit is revalidated against the exact pair
    // before reuse and a drifted entry is discarded. Facade terminal-alias resolution shares
    // this cache under the same revalidation rule (D15a: path compression may be cached but
    // never replaces durable edges).
    private val liveKeys = MutableStateFlow<Map<KeyIdentity, K>>(emptyMap())

    // D15a's in-memory normalized alias table: same-namespace full-pair redirects with
    // PENDING/ACTIVE states plus generation-idempotency receipts. A same-process preview only;
    // 022/023 rebuild routing over durable records.
    private val aliasRouter =
        InMemoryAliasRouter(
            storage =
                (journal as? StorageBackedMutationJournal<V>)?.storage
                    ?: InMemoryMutationJournalStorage(),
            runtimeState =
                (journal as? StorageBackedMutationJournal<V>)?.runtimeState
                    ?: MutationRuntimeState<Any>(),
        )

    // D14/D15a's lost-wakeup-free per-terminal-identity signals, both mutation-owned stateful
    // monotonic counters:
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

    // R-0 §1's contiguous locally retired prefix, in-memory form, advertised on pushes (D15a).
    private var retiredThroughSequence = 0L
    private var serverConfirmedRetiredThroughSequence = 0L
    private val retiredSequences = mutableSetOf<Long>()

    internal val changes: SharedFlow<StoreKey> = signalSink.asSharedFlow()
    internal val poisoned: SharedFlow<PoisonedIntent> = poisonSink.asSharedFlow()

    /** The advisory lifecycle bus republished by the facade; 023 owns causal emission. */
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
            hydratedTombstones.clear()
            hydratedTombstones += snapshot.tombstones
            val executionsBySequence = snapshot.executions.associateBy { it.clientSequence }
            val attemptsByIdentity =
                snapshot.attempts.associateBy { it.clientSequence to it.generation }
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
            deadLettersByMutationId.clear()
            codecBlockedMutationIds.clear()
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

                if (phase == MutationExecutionPhase.PARKED) {
                    val failure = execution.activeFailureId?.let(failuresById::get) ?: continue
                    deadLettersByMutationId[intent.mutationId] =
                        DeadLetter(
                            namespace = intent.namespace,
                            canonicalId = intent.canonicalId,
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

                var blocked = false
                val preAck =
                    phase == MutationExecutionPhase.UNPREPARED ||
                        phase == MutationExecutionPhase.READY ||
                        phase == MutationExecutionPhase.INFLIGHT ||
                        phase == MutationExecutionPhase.REFRESH_REQUIRED
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
                            existing = existingCodecFailures,
                        )
                        blocked = true
                    }
                }
                if (blocked) codecBlockedMutationIds += intent.mutationId
                val attemptedIdentity =
                    durableAttempts[intent.mutationId]?.let { attempt ->
                        KeyIdentity(attempt.effectiveNamespace, attempt.effectiveCanonicalId)
                    } ?: KeyIdentity(intent.namespace, intent.canonicalId)
                val effectiveIdentity = terminalIdentity(attemptedIdentity, hydratedAliases)
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

    private suspend fun classifyHydrationCodecFailure(
        intent: MutationIntentRecord,
        generation: Int,
        detail: String,
        message: String,
        existing: MutableSet<Triple<Long, Int, String>>,
    ) {
        codecBlockedMutationIds += intent.mutationId
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
        // global enqueue/activation gate. D14 still gets exactly one resolution attempt.
        val resolvedKey = requireTerminalKey(key)
        val originalIdentity = key.identity()
        val (mutationId, effectiveKey) =
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
                journal.append(
                    identity,
                    JournalEntry(
                        mutationId = nextId,
                        mutatorId = ref.id,
                        args = args,
                        clientSequence = sequence,
                        createdAtEpochMillis = wallClock.nowEpochMillis(),
                    ),
                )
                nextMutationSequence = sequence
                cacheLiveKey(identity, appendKey)
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
                nextId to appendKey
            }
        signalChange(effectiveKey)
        return mutationId
    }

    internal fun bind(handle: StoreWriteHandle<K, V>) {
        check(!this::handle.isInitialized) {
            "Mutation engine is already bound."
        }
        this.handle = handle
    }

    /**
     * One idempotent keyed foreground pass (D12): captures the unprojected confirmed base through
     * the ordered `status -> LocalOnly` loop, then pushes the pending FIFO prefix once with no
     * retry, backoff, or parking. A key without pending work is a no-op that reads nothing. The
     * facade resolves the terminal alias identity before calling this (D15a); a mid-pass
     * activation re-homes the pass to the canonical key and continues the sequence-merged prefix
     * there.
     */
    internal suspend fun drain(key: K) {
        ensureHydrated()
        drainIdentity(key)
    }

    /**
     * One idempotent global foreground pass (D12): enumerates durable identities from the
     * journal, reconstructs each `K` through the retained resolver with exact-pair validation
     * (D14), and continues past identities that fail to resolve after recording a normalized
     * in-memory failure carrier. Processing is deterministic by durable client sequence within
     * an identity and enumerates identities in first-enqueue order; no cross-key order is
     * promised. Scheduling, parking, and checkpoint flushing are 022/023's.
     */
    internal suspend fun drain() {
        ensureHydrated()
        for (identity in journal.identities()) {
            val key = resolveForDrain(identity) ?: continue
            drainIdentity(key)
        }
    }

    /** D12/D14: drops every cached resolution so the next global drain must reconstruct. */
    internal fun clearLiveKeyCache() {
        liveKeys.value = emptyMap()
    }

    /**
     * The normalized in-memory drain failure carriers recorded so far (D3/D14/D15a in 021 form):
     * resolver `IDENTITY` failures and alias `PROTOCOL` failures. No original `Throwable` or
     * `StoreError` is retained; 022/023 own the durable failure rows and the parking these
     * carriers preview.
     */
    internal fun drainFailuresForInspection(): List<MutationFailure> = drainFailures.toList()

    internal suspend fun pending(key: K): List<PendingIntent> {
        ensureHydrated()
        return pendingForIdentity(key.identity())
    }

    /** Snapshot terminal routing and rows together in durable client-sequence order (D3/D15a). */
    internal fun pendingForIdentity(identity: KeyIdentity): List<PendingIntent> {
        val cache = journal as? StorageBackedMutationJournal<V>
        if (cache == null) {
            return pendingRows(aliasRouter.terminalOf(identity))
        }
        val snapshot = cache.runtimeSnapshot()
        val terminal = terminalIdentity(identity, snapshot.aliases)
        return snapshot.entries[terminal]
            .orEmpty()
            .sortedBy { entry -> entry.clientSequence }
            .map { entry -> pendingRow(terminal, entry) }
    }

    /**
     * Snapshot rows for every durable identity in durable client-sequence order (D3): the
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
                entries.map { entry ->
                    entry.clientSequence to pendingRow(identity, entry)
                }
            }
            .sortedBy { (clientSequence, _) -> clientSequence }
            .map { (_, row) -> row }
    }

    /**
     * Durably parked intents only (D3). Always empty at 021: parking is 023's transition over
     * 022's durable rows, and the walking skeleton never fakes it. The normalized failure
     * carrier those rows will hold is already real — see [drainFailuresForInspection].
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
    // Canonical alias routing doors used by the facade (D14/D15a).
    // -----------------------------------------------------------------------------------------

    /** The terminal identity for [identity] under the active alias edges (D15a). */
    internal fun terminalIdentityOf(identity: KeyIdentity): KeyIdentity =
        aliasRouter.terminalOf(identity)

    /**
     * D15a's mutation-owned stateful alias revision for [identity]. It advances synchronously
     * inside the NonCancellable retirement/activation handoff of a redirect whose source is
     * [identity]; a live facade stream re-resolves on a strictly newer value.
     */
    internal fun aliasRevision(identity: KeyIdentity): StateFlow<Long> =
        aliasRevisionSignal(identity)

    /**
     * D14's mutation-owned stateful resolution pulse for [identity]. It advances on alias
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
     * One terminal-identity resolution attempt for a facade entry point (D14/D15a): the given
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
                message = failure.message.orEmpty(),
                cause = null,
            )
        }
    }

    /**
     * The suspending-facade resolution door (D14): one attempt, then the sanctioned
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
     * The keyed-drain resolution door (D14): keyed drain sits in the park-not-throw camp for a
     * pre-ack row it cannot resolve, so a failed terminal resolution records the normalized
     * `IDENTITY` carrier and returns normally — the 021 analogue of the durable pre-ack park
     * that 023 converts these halts into. The attempt-or-success advances the identity's
     * resolution pulse exactly like every non-stream facade attempt.
     */
    internal suspend fun drainKeyResolvedOrRecord(key: K) {
        val resolution = resolveTerminalKey(key)
        bumpResolutionPulse(resolution.identity)
        when (resolution) {
            is TerminalKeyResolution.Resolved -> drain(resolution.key)
            is TerminalKeyResolution.Failed ->
                recordNormalizedFailure(
                    kind = MutationFailureKind.IDENTITY,
                    detail = DRAIN_FAILURE_DETAIL_KEYED_TERMINAL_UNRESOLVED,
                    message = resolution.message,
                )
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
        journal.pendingSnapshot(identity).sortedBy { entry -> entry.clientSequence }

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
            // A journalled row can never be PARKED or RETIRED at 021; PENDING is the total
            // mapping's only legal fallback for an unobserved phase.
            ?: MutationPendingState.PENDING

    /**
     * D14's exact-pair resolution for global drain. Cache hits are revalidated verbatim before
     * reuse; failures record one normalized in-memory `IDENTITY` carrier — never the original
     * `Throwable` — and return null so independent identities continue. `CancellationException`
     * is always rethrown.
     */
    private suspend fun resolveForDrain(identity: KeyIdentity): K? {
        cachedLiveKey(identity)?.let { cached -> return cached }
        val requested = MutationKeyIdentity(identity.namespace, identity.canonicalId)
        val resolved =
            try {
                keyResolver.resolve(requested)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                recordNormalizedFailure(
                    kind = MutationFailureKind.IDENTITY,
                    detail = DRAIN_FAILURE_DETAIL_RESOLVER_THROW,
                    message = failure.message ?: "MutationKeyResolver threw without a message.",
                )
                return null
            }
        return try {
            requireResolvedKey(requested, resolved).also { validated ->
                cacheLiveKey(identity, validated)
            }
        } catch (failure: IllegalStateException) {
            recordNormalizedFailure(
                kind = MutationFailureKind.IDENTITY,
                detail =
                    if (resolved == null) {
                        DRAIN_FAILURE_DETAIL_RESOLVER_NULL
                    } else {
                        DRAIN_FAILURE_DETAIL_IDENTITY_MISMATCH
                    },
                message = failure.message.orEmpty(),
            )
            null
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
     * The keyed foreground pass. The base is captured once through the ordered loop; after each
     * in-pass adoption the acknowledged authoritative presence becomes the next entry's base and
     * its metadata is re-read from the retained bookkeeper, where confirmation has already been
     * recorded and therefore cannot lead the adopted value.
     *
     * Alias interaction (D15a): the pass drains the durable-client-sequence prefix that existed
     * when it started, selecting the lowest pending sequence at the CURRENT effective identity
     * each step. When a Present acknowledgement's redirect activates mid-pass, the pass re-homes
     * to the canonical key and continues the sequence-merged siblings there. An acknowledgement
     * that fails alias-protocol validation records one normalized `PROTOCOL` carrier, returns
     * the intent to `READY` with a completed-attempt fact, performs no adoption, and halts this
     * key's pass; 023 owns the durable park transition that halt previews.
     */
    private suspend fun drainIdentity(key: K) {
        if (durableJournal == null) {
            drainIdentityLegacy(key)
        } else {
            drainIdentityDurable(key)
        }
    }

    /** Protected 021 path for direct, codec-less engine constructions in module tests. */
    private suspend fun drainIdentityLegacy(key: K) {
        var currentKey = key
        val sequenceBound = mutations.withLock { nextMutationSequence }
        var entry = nextEligible(currentKey.identity(), sequenceBound) ?: return
        val captured = captureBase(currentKey)
        var base = captured.presence
        var baseMeta = captured.meta
        while (true) {
            val outcome = project(base, entry)
            if (!outcome.advanced) return
            val mine = outcome.value
            if (!captureEffects(currentKey, entry)) return
            val push = buildPush(currentKey, entry, base, mine, baseMeta)
            phases[entry.mutationId] = MutationExecutionPhase.INFLIGHT
            val ack =
                try {
                    server.push(push)
                } catch (failure: Throwable) {
                    // Transport cancellation is not failure: INFLIGHT stays intact and the next
                    // pass replays the same immutable generation (D2). Only a non-cancellation
                    // failure records a completed-attempt fact and returns to READY.
                    if (failure is CancellationException) throw failure
                    completedAttempts[entry.mutationId] =
                        (completedAttempts[entry.mutationId] ?: 0) + 1
                    phases[entry.mutationId] = MutationExecutionPhase.READY
                    return
                }
            when (ack) {
                is MutationPresentAck -> {
                    val adoption =
                        adoptPresent(currentKey, entry, push.idempotencyKey, ack) ?: return
                    currentKey = adoption.effectiveKey
                    base = MutationPresence.Present(adoption.adopted)
                    val next = nextEligible(currentKey.identity(), sequenceBound) ?: return
                    baseMeta = snapshotMeta(bookkeeper.status(currentKey)?.meta)
                    entry = next
                }
                is MutationAbsentAck -> {
                    adoptAbsent(currentKey, entry)
                    base = MutationPresence.Absent
                    baseMeta = null
                    entry = nextEligible(currentKey.identity(), sequenceBound) ?: return
                }
            }
        }
    }

    /**
     * Durable foreground pass. Phase dispatch happens before any consumer callback: READY and
     * INFLIGHT replay their immutable attempt, ACKED resumes adoption, EFFECTS_PENDING resumes
     * only retirement finalization, and only UNPREPARED may capture/project/prepare a generation.
     */
    private suspend fun drainIdentityDurable(key: K) {
        var currentKey = key
        val sequenceBound = mutations.withLock { nextMutationSequence }
        var entry = nextEligible(currentKey.identity(), sequenceBound) ?: return
        while (true) {
            if (entry.mutationId in codecBlockedMutationIds) return
            val phase = phases[entry.mutationId] ?: MutationExecutionPhase.UNPREPARED
            when (phase) {
                MutationExecutionPhase.UNPREPARED -> {
                    if (prepareDurableAttempt(currentKey, entry) == null) return
                    markDurableInflight(entry)
                }

                MutationExecutionPhase.READY -> markDurableInflight(entry)

                MutationExecutionPhase.INFLIGHT -> Unit

                MutationExecutionPhase.ACKED -> {
                    currentKey = resumeDurableAck(currentKey, entry) ?: return
                    entry = nextEligible(currentKey.identity(), sequenceBound) ?: return
                    continue
                }

                MutationExecutionPhase.EFFECTS_PENDING -> {
                    currentKey = resumeDurableEffectsPending(currentKey, entry) ?: return
                    entry = nextEligible(currentKey.identity(), sequenceBound) ?: return
                    continue
                }

                MutationExecutionPhase.REFRESH_REQUIRED,
                MutationExecutionPhase.PARKED,
                MutationExecutionPhase.RETIRED,
                -> return
            }

            val attempt = requireNotNull(durableAttempts[entry.mutationId])
            val push = buildPushFromDurableAttempt(currentKey, entry, attempt)
            val ack =
                try {
                    server.push(push)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    recordDurableTransportFailure(entry, failure)
                    return
                }
            currentKey =
                when (ack) {
                    is MutationPresentAck ->
                        adoptDurablePresent(currentKey, entry, attempt, ack)?.effectiveKey ?: return
                    is MutationAbsentAck -> {
                        if (!adoptDurableAbsent(currentKey, entry, attempt, ack)) return
                        currentKey
                    }
                }
            entry = nextEligible(currentKey.identity(), sequenceBound) ?: return
        }
    }

    private suspend fun prepareDurableAttempt(
        key: K,
        entry: JournalEntry<V>,
    ): MutationAttemptRecord? {
        val durable = requireNotNull(durableJournal)
        val captured = captureBase(key)
        val projected = project(captured.presence, entry)
        if (!projected.advanced) return null
        val effects = evaluateEffects(key, entry) ?: return null
        val codec = checkNotNull(valueCodec)
        val preparedAt = wallClock.nowEpochMillis()
        val attempt =
            MutationAttemptRecord(
                clientId = clientId,
                clientSequence = entry.durableClientSequence,
                generation = 1,
                effectiveNamespace = key.namespace.value,
                effectiveCanonicalId = key.canonicalId(),
                valueCodecVersion = valueCodecVersion,
                basePresence = captured.presence.toPresenceState(),
                baseBlob = captured.presence.encodePresentOrNull(codec),
                minePresence = projected.value.toPresenceState(),
                mineBlob = projected.value.encodePresentOrNull(codec),
                preconditionMetaPresent = captured.meta != null,
                preconditionWrittenAt = captured.meta?.writtenAtEpochMillis,
                preconditionEtag = captured.meta?.etag,
                advertisedRetiredThroughSequence = retiredThroughSequence,
                generationIdempotencyKey =
                    "$clientId:${entry.durableClientSequence}:g$IN_MEMORY_GENERATION",
                preparedAt = preparedAt,
                conflictMetaPresent = null,
                conflictWrittenAt = null,
                conflictEtag = null,
                conflictReceivedAt = null,
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
        durableExecutions[entry.mutationId] = ready
        phases[entry.mutationId] = MutationExecutionPhase.READY
        completedAttempts[entry.mutationId] = 0
        return attempt
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

    private suspend fun adoptDurablePresent(
        key: K,
        entry: JournalEntry<V>,
        attempt: MutationAttemptRecord,
        ack: MutationPresentAck<K, V>,
    ): PresentAdoption<K, V>? {
        val source = key.identity()
        val claimed = ack.canonicalKey?.identity()
        val admission =
            aliasRouter.admit(
                source = source,
                claimed = claimed,
                idempotencyKey = attempt.generationIdempotencyKey,
                createdByClientId = clientId,
                createdBySequence = entry.durableClientSequence,
                createdAt = wallClock.nowEpochMillis(),
            )
        val admitted =
            when (admission) {
                is AliasAdmission.Rejected -> {
                    recordDurableProtocolFailure(entry, admission)
                    return null
                }
                is AliasAdmission.Admitted -> admission
            }
        val redirect = admitted.redirect
        val ackRecord = recordDurableAck(entry, attempt, ack, admitted)
        val authoritative =
            checkNotNull(valueCodec).decodeCopied(
                ackRecord.valueCodecVersion,
                checkNotNull(ackRecord.authoritativeBlob),
            )
        val effectiveKey =
            if (redirect == null) {
                key
            } else {
                resolveExecutionIdentity(redirect.target, checkNotNull(ack.canonicalKey))
            }
        handle.apply(effectiveKey, authoritative)
        handle.confirmFresh(effectiveKey, ackRecord.etag)
        persistDurableEffectsPending(entry)
        val retired =
            if (redirect == null) {
                finalizeDurableRetirement(key, entry)
            } else {
                finalizeDurableAliasRetirement(key, effectiveKey, entry)
            }
        return if (retired) PresentAdoption(authoritative, effectiveKey) else null
    }

    private suspend fun adoptDurableAbsent(
        key: K,
        entry: JournalEntry<V>,
        attempt: MutationAttemptRecord,
        ack: MutationAbsentAck<K, V>,
    ): Boolean {
        recordDurableAck(entry, attempt, ack)
        absentAdoption(key)
        persistDurableEffectsPending(entry)
        return finalizeDurableRetirement(key, entry)
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
                        attempt.valueCodecVersion,
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
                        attempt.valueCodecVersion,
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
        durable.storage.transaction { transaction ->
            aliasAdmission?.pendingRecord?.let(transaction::insertAlias)
            transaction.insertAck(record)
            transaction.advanceExecution(acknowledged)
        }
        if (aliasAdmission != null) {
            aliasRouter.publishAdmission(aliasAdmission)
        }
        durableAcks[entry.mutationId] = record
        durableExecutions[entry.mutationId] = acknowledged
        phases[entry.mutationId] = MutationExecutionPhase.ACKED
        completedAttempts[entry.mutationId] = acknowledged.attempt
        return record
    }

    private suspend fun resumeDurableAck(
        key: K,
        entry: JournalEntry<V>,
    ): K? {
        val ack = requireNotNull(durableAcks[entry.mutationId])
        return when (ack.authoritativePresence) {
            MutationPresenceState.PRESENT -> {
                val effectiveKey =
                    if (ack.canonicalTargetNamespace == null) {
                        key
                    } else {
                        resolveExecutionIdentity(
                            KeyIdentity(
                                checkNotNull(ack.canonicalTargetNamespace),
                                checkNotNull(ack.canonicalTargetId),
                            ),
                        )
                    }
                val authoritative =
                    checkNotNull(valueCodec).decodeCopied(
                        ack.valueCodecVersion,
                        checkNotNull(ack.authoritativeBlob),
                )
                handle.apply(effectiveKey, authoritative)
                handle.confirmFresh(effectiveKey, ack.etag)
                persistDurableEffectsPending(entry)
                val retired =
                    if (effectiveKey.identity() == key.identity()) {
                        finalizeDurableRetirement(key, entry)
                    } else {
                        finalizeDurableAliasRetirement(key, effectiveKey, entry)
                    }
                effectiveKey.takeIf { retired }
            }
            MutationPresenceState.ABSENT -> {
                absentAdoption(key)
                persistDurableEffectsPending(entry)
                key.takeIf { finalizeDurableRetirement(key, entry) }
            }
        }
    }

    /**
     * Resumes only retirement finalization for a durably adopted generation. Adoption and
     * transport are never repeated from `EFFECTS_PENDING`; pending effect rows leave the intent
     * at this boundary for Issue 023's effect executor.
     */
    private suspend fun resumeDurableEffectsPending(
        key: K,
        entry: JournalEntry<V>,
    ): K? {
        val ack = requireNotNull(durableAcks[entry.mutationId])
        val effectiveKey =
            if (
                ack.authoritativePresence == MutationPresenceState.PRESENT &&
                ack.canonicalTargetNamespace != null
            ) {
                resolveExecutionIdentity(
                    KeyIdentity(
                        checkNotNull(ack.canonicalTargetNamespace),
                        checkNotNull(ack.canonicalTargetId),
                    ),
                )
            } else {
                key
            }
        val retired =
            if (effectiveKey.identity() == key.identity()) {
                finalizeDurableRetirement(key, entry)
            } else {
                finalizeDurableAliasRetirement(key, effectiveKey, entry)
            }
        return effectiveKey.takeIf { retired }
    }

    private suspend fun resolveExecutionIdentity(
        acknowledged: KeyIdentity,
        acknowledgedKey: K? = null,
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
        val requested = MutationKeyIdentity(terminal.namespace, terminal.canonicalId)
        return requireResolvedKey(requested, keyResolver.resolve(requested)).also { resolved ->
            cacheLiveKey(terminal, resolved)
        }
    }

    private suspend fun finalizeDurableRetirement(
        key: K,
        entry: JournalEntry<V>,
    ): Boolean =
        withContext(NonCancellable) {
            if (!persistDurableRetirement(entry)) return@withContext false
            journal.retire(key.identity(), entry.mutationId)
            phases.remove(entry.mutationId)
            completedAttempts.remove(entry.mutationId)
            signalSink.emit(key)
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
            val retired =
                mutations.withLock {
                    val activation = aliasRouter.activationRecord(source, wallClock.nowEpochMillis())
                    if (!persistDurableRetirement(entry, activation)) return@withLock false
                    requireNotNull(durableJournal).publishAliasRetirement(
                        source = source,
                        terminalTarget = target,
                        retiredMutationId = entry.mutationId,
                    )
                    cacheLiveKey(target, targetKey)
                    phases.remove(entry.mutationId)
                    completedAttempts.remove(entry.mutationId)
                    aliasRevisionSignal(source).update { revision -> revision + 1L }
                    bumpResolutionPulse(source)
                    bumpResolutionPulse(target)
                    true
                }
            if (!retired) return@withContext false
            signalSink.emit(sourceKey)
            signalSink.emit(targetKey)
            true
        }
    }

    /** Commits R-0 rule 6's adoption advance independently of retirement finalization. */
    private suspend fun persistDurableEffectsPending(entry: JournalEntry<V>) {
        val durable = requireNotNull(durableJournal)
        val previous = requireNotNull(durableExecutions[entry.mutationId])
        require(previous.phase == StoredExecutionPhase.ACKED) {
            "Durable adoption advance requires ACKED, but was ${previous.phase}."
        }
        val effectsPending = previous.copyExecution(phase = StoredExecutionPhase.EFFECTS_PENDING)
        durable.storage.transaction { transaction ->
            transaction.advanceExecution(effectsPending)
        }
        durableExecutions[entry.mutationId] = effectsPending
        phases[entry.mutationId] = MutationExecutionPhase.EFFECTS_PENDING
    }

    /** Returns true only when no durable PENDING effect prevented retirement. */
    private suspend fun persistDurableRetirement(
        entry: JournalEntry<V>,
        aliasActivation: MutationKeyAliasRecord? = null,
    ): Boolean {
        val durable = requireNotNull(durableJournal)
        val sequence = entry.durableClientSequence
        val previous = requireNotNull(durableExecutions[entry.mutationId])
        require(previous.phase == StoredExecutionPhase.EFFECTS_PENDING) {
            "Durable retirement finalization requires EFFECTS_PENDING, but was ${previous.phase}."
        }
        val retiredAt = wallClock.nowEpochMillis()
        var retired = false
        var committedPrefix = retiredThroughSequence
        var committedConfirmed = serverConfirmedRetiredThroughSequence
        var committedExecution = previous
        durable.storage.transaction { transaction ->
            val effectsPending =
                transaction.effects(clientId).any { effect ->
                    effect.clientSequence == sequence &&
                        effect.disposition ==
                        org.mobilenativefoundation.store6.mutations.storage.MutationEffectDisposition.PENDING
                }
            if (effectsPending) return@transaction
            val retiredRecord =
                previous.copyExecution(
                    phase = StoredExecutionPhase.RETIRED,
                    retiredAt = retiredAt,
                )
            aliasActivation?.let(transaction::advanceAlias)
            transaction.advanceExecution(retiredRecord)
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
            committedExecution = retiredRecord
            committedPrefix = prefix
            committedConfirmed = client.serverConfirmedRetiredThroughSequence
            retired = true
        }
        durableExecutions[entry.mutationId] = committedExecution
        if (!retired) {
            phases[entry.mutationId] = MutationExecutionPhase.EFFECTS_PENDING
            return false
        }
        retiredSequences += sequence
        while (retiredSequences.remove(retiredThroughSequence + 1L)) {
            retiredThroughSequence += 1L
        }
        check(retiredThroughSequence == committedPrefix)
        serverConfirmedRetiredThroughSequence = committedConfirmed
        return true
    }

    /**
     * The lowest pending durable client sequence at [identity] within this pass's enqueue bound:
     * the pass drains the prefix that existed when it started and never chases intents enqueued
     * behind it (D12). Ties (direct journal appends in module tests use the default sequence)
     * keep first-append order.
     */
    private fun nextEligible(
        identity: KeyIdentity,
        sequenceBound: Long,
    ): JournalEntry<V>? =
        journal
            .pendingSnapshot(identity)
            .filter { entry -> entry.clientSequence <= sequenceBound }
            .minByOrNull { entry -> entry.clientSequence }

    /**
     * The ordered base-capture loop (Shared invariants; R1-18). A present value accepts the
     * first (pre-value) status metadata, which may match or lag but cannot lead the value under
     * Store's commit ordering. On a missing value, status is read again and absence is accepted
     * only when BOTH bracketing statuses carry no metadata; otherwise a concurrent
     * fetch-deletion or key/namespace/all-clear window is open and the loop retries. The loop,
     * not any single bracket, is the correctness mechanism.
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
            return ProjectionOutcome(value = base, advanced = false)
        }
        val registration =
            registry.registrations[entry.mutatorId]
                ?: return ProjectionOutcome(value = base, advanced = false)
        return try {
            val result = registration.project(base, entry.args)
            if (result == null) {
                // D13: a null projector result means decline only — passthrough in projection,
                // halt of the same-key suffix in drain, never a deletion.
                ProjectionOutcome(value = base, advanced = false)
            } else {
                ProjectionOutcome(value = result, advanced = true)
            }
        } catch (failure: Throwable) {
            // Containment swallows everything including CancellationException: a rethrow out of
            // Overlay.apply terminalizes the key's projected streams permanently (008 contract).
            poisonSink.tryEmit(
                PoisonedIntent(
                    mutationId = entry.mutationId,
                    mutatorId = entry.mutatorId,
                    failure = failure,
                ),
            )
            ProjectionOutcome(value = base, advanced = false)
        }
    }

    /**
     * Builds the immutable in-memory attempt generation (D2, R-0 §4): stable client identity and
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
     * D13/D15a's present adoption: validate the acknowledgement and its optional canonical
     * target, then `apply -> confirmFresh` at the EFFECTIVE canonical key, then retire — and for
     * a redirect, activate the alias inside the same `NonCancellable` accepted-state handoff
     * that advances the mutation-owned alias revision (D15a steps 1/3/5, in-memory analog; the
     * durable steps 2 and 4 are 022/023's).
     *
     * The authoritative value is rebuilt through the codec's copy boundaries before adoption so
     * a server retaining its acknowledged object cannot mutate adopted state or the echo-forward
     * base. Adoption failure propagates; the intent stays pending. An alias-protocol violation
     * (cross-namespace target, retarget, cycle, or a generation retry acknowledging a different
     * canonical target) records one normalized `PROTOCOL` carrier, returns the intent to
     * `READY`, performs no adoption, and returns null so the pass halts.
     */
    private suspend fun adoptPresent(
        key: K,
        entry: JournalEntry<V>,
        idempotencyKey: String,
        ack: MutationPresentAck<K, V>,
    ): PresentAdoption<K, V>? {
        val source = key.identity()
        val claimed = ack.canonicalKey?.identity()
        val admission =
            aliasRouter.admit(
                source = source,
                claimed = claimed,
                idempotencyKey = idempotencyKey,
                createdByClientId = clientId,
                createdBySequence = entry.durableClientSequence,
                createdAt = wallClock.nowEpochMillis(),
            )
        val admitted =
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
                    return null
                }
                is AliasAdmission.Admitted -> admission
            }
        aliasRouter.commitAdmission(admitted)
        val redirect = admitted.redirect
        phases[entry.mutationId] = MutationExecutionPhase.ACKED
        val authoritative = copiedValue(ack.authoritative)
        val effectiveKey =
            if (redirect == null) {
                key
            } else {
                resolveExecutionIdentity(redirect.target, checkNotNull(ack.canonicalKey))
            }
        handle.apply(effectiveKey, authoritative)
        handle.confirmFresh(effectiveKey, ack.etag)
        if (redirect == null) {
            retire(key, entry)
        } else {
            retireAndActivateAlias(
                sourceKey = key,
                targetKey = effectiveKey,
                entry = entry,
            )
        }
        return PresentAdoption(adopted = authoritative, effectiveKey = effectiveKey)
    }

    /**
     * D13's absent adoption: the acknowledged state is recorded, confirmed absence is adopted
     * through the bound `Store.clear` door, and only then does the intent retire and signal.
     * The sealed [MutationAbsentAck] carries no canonical key, so rekey-on-deletion is
     * unrepresentable (D15a). Issue 022 lands tombstone storage and hydration; the ack/clear and
     * activation transitions remain 023/024-owned.
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
     * D15a step 5's in-memory analog, one accepted-state handoff (Shared invariants): inside a
     * single `NonCancellable` block the intent retires, the redirect activates, the source's
     * queued siblings re-home to the canonical identity (merged by durable client sequence —
     * selection is sequence-ordered, so physical order is not authority), the canonical `K` is
     * cached, and the mutation-owned alias revision and resolution pulses advance synchronously
     * BEFORE the compatibility `Overlay.changes` signals are emitted. Caller cancellation after
     * the in-memory commit therefore cannot strand a live provisional stream. Durable
     * transactional coordination is 024's (R1-23); restart rehydration is 022's.
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

    /** Advances the in-memory contiguous retired prefix (D15a); gaps hold the high-water. */
    private fun recordRetiredSequence(clientSequence: Long) {
        if (clientSequence <= retiredThroughSequence) return
        retiredSequences += clientSequence
        while (retiredSequences.remove(retiredThroughSequence + 1)) {
            retiredThroughSequence += 1
        }
    }

    /**
     * Captures the intent's normalized invalidation-effect snapshot before its first push
     * (D8/R-0 §7). A throwing `stales` function is contained exactly like a throwing projector —
     * ephemeral poison, no transport — and halts this key's pass. 021 never executes effects.
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

    /** The captured (never executed) effect snapshot for a mutation; 022 owns durability. */
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

    /** A library-owned immutable snapshot of captured metadata fields (D2). */
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
)

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

/** One completed present adoption: the adopted value and the effective (possibly rekeyed) key. */
private class PresentAdoption<K : StoreKey, V : Any>(
    val adopted: V,
    val effectiveKey: K,
)
