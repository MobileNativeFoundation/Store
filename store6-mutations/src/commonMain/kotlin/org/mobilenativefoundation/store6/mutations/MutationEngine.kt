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

    // In-memory execution bookkeeping for truthful inspection (D3/R-0 §3). All of it is
    // rewritten over durable records at 022/023.
    private val phases = mutableMapOf<String, MutationExecutionPhase>()
    private val completedAttempts = mutableMapOf<String, Int>()
    private val drainFailures = mutableListOf<MutationFailure>()

    // D12/D14: the live key map is CACHE ONLY. Global drain's correctness path is the durable
    // journal identity plus the resolver; every cache hit is revalidated against the exact pair
    // before reuse and a drifted entry is discarded. Facade terminal-alias resolution shares
    // this cache under the same revalidation rule (D15a: path compression may be cached but
    // never replaces durable edges).
    private val liveKeys = mutableMapOf<KeyIdentity, K>()

    // D15a's in-memory normalized alias table: same-namespace full-pair redirects with
    // PENDING/ACTIVE states plus generation-idempotency receipts. A same-process preview only;
    // 022/023 rebuild routing over durable records.
    private val aliasRouter = InMemoryAliasRouter()

    // D14/D15a's lost-wakeup-free per-terminal-identity signals, both mutation-owned stateful
    // monotonic counters:
    // - [aliasRevisionSignals] advances only inside the NonCancellable retirement/activation
    //   handoff of a redirect's source identity; a live facade stream re-resolves on a strictly
    //   newer value and swaps delegates.
    // - [resolutionPulseSignals] advances on every activation AND on every explicit non-stream
    //   facade/drain resolution attempt-or-success for an identity; a facade stream waiting
    //   after a resolver failure retries on a strictly newer value. A stream's own attempt
    //   never advances either signal.
    private val aliasRevisionSignals = mutableMapOf<KeyIdentity, MutableStateFlow<Long>>()
    private val resolutionPulseSignals = mutableMapOf<KeyIdentity, MutableStateFlow<Long>>()

    // R-0 §1's contiguous locally retired prefix, in-memory form, advertised on pushes (D15a).
    private var retiredThroughSequence = 0L
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

    internal suspend fun <A : Any> mutate(
        key: K,
        ref: MutatorRef<K, V, A>,
        args: A,
    ): String {
        require(ref.ownership === registry.ownership) {
            "MutatorRef '${ref.id}' belongs to a different MutatorRegistry."
        }
        val identity = key.identity()
        val mutationId =
            mutations.withLock {
                nextMutationSequence += 1
                val sequence = nextMutationSequence
                val nextId = "mutation-$sequence"
                liveKeys[identity] = key
                phases[nextId] = MutationExecutionPhase.UNPREPARED
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
            }
        signalChange(key)
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
        for (identity in journal.identities()) {
            val key = resolveForDrain(identity) ?: continue
            drainIdentity(key)
        }
    }

    /** D12/D14: drops every cached resolution so the next global drain must reconstruct. */
    internal fun clearLiveKeyCache() {
        liveKeys.clear()
    }

    /**
     * The normalized in-memory drain failure carriers recorded so far (D3/D14/D15a in 021 form):
     * resolver `IDENTITY` failures and alias `PROTOCOL` failures. No original `Throwable` or
     * `StoreError` is retained; 022/023 own the durable failure rows and the parking these
     * carriers preview.
     */
    internal fun drainFailuresForInspection(): List<MutationFailure> = drainFailures.toList()

    internal suspend fun pending(key: K): List<PendingIntent> = pendingRows(key.identity())

    /** Snapshot rows for one durable identity in durable client-sequence order (D3/D15a). */
    internal fun pendingForIdentity(identity: KeyIdentity): List<PendingIntent> =
        pendingRows(identity)

    /**
     * Snapshot rows for every durable identity in durable client-sequence order (D3): the
     * per-client sequence is the FIFO and watermark unit, so the global view sorts by it.
     */
    internal suspend fun pendingWrites(): List<PendingIntent> =
        journal
            .identities()
            .flatMap { identity ->
                journal.pendingSnapshot(identity).map { entry ->
                    entry.clientSequence to pendingRow(identity, entry)
                }
            }
            .sortedBy { (clientSequence, _) -> clientSequence }
            .map { (_, row) -> row }

    /**
     * Durably parked intents only (D3). Always empty at 021: parking is 023's transition over
     * 022's durable rows, and the walking skeleton never fakes it. The normalized failure
     * carrier those rows will hold is already real — see [drainFailuresForInspection].
     */
    internal fun deadLetters(): List<DeadLetter> = emptyList()

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
        val terminal = aliasRouter.terminalOf(key.identity())
        if (terminal == key.identity()) {
            return TerminalKeyResolution.Resolved(key, terminal)
        }
        liveKeys[terminal]?.let { cached ->
            if (
                cached.namespace.value == terminal.namespace &&
                cached.canonicalId() == terminal.canonicalId
            ) {
                return TerminalKeyResolution.Resolved(cached, terminal)
            }
            liveKeys.remove(terminal)
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
                        liveKeys[terminal] = validated
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
        aliasRevisionSignals.getOrPut(identity) { MutableStateFlow(0L) }

    private fun resolutionPulseSignal(identity: KeyIdentity): MutableStateFlow<Long> =
        resolutionPulseSignals.getOrPut(identity) { MutableStateFlow(0L) }

    private fun bumpResolutionPulse(identity: KeyIdentity) {
        resolutionPulseSignal(identity).value += 1
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
        liveKeys[identity]?.let { cached ->
            if (
                cached.namespace.value == identity.namespace &&
                cached.canonicalId() == identity.canonicalId
            ) {
                return cached
            }
            liveKeys.remove(identity)
        }
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
                liveKeys[identity] = validated
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
        val admission = aliasRouter.admit(source, claimed, idempotencyKey)
        val redirect =
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
                is AliasAdmission.Admitted -> admission.redirect
            }
        phases[entry.mutationId] = MutationExecutionPhase.ACKED
        val authoritative = copiedValue(ack.authoritative)
        val effectiveKey = if (redirect == null) key else checkNotNull(ack.canonicalKey)
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
     * unrepresentable (D15a). The tombstone generation this adoption will persist is 022's
     * (R1-21); 021 records none.
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
            journal.retire(source, entry.mutationId)
            aliasRouter.activate(source)
            for (sibling in orderedPending(source)) {
                journal.retire(source, sibling.mutationId)
                journal.append(target, sibling)
            }
            liveKeys[target] = targetKey
            phases.remove(entry.mutationId)
            completedAttempts.remove(entry.mutationId)
            recordRetiredSequence(entry.clientSequence)
            // The synchronous stateful revision handoff: no cancellable suspension may separate
            // the in-memory commit above from these advances.
            aliasRevisionSignal(source).value += 1
            bumpResolutionPulse(source)
            bumpResolutionPulse(target)
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
        val registration = registry.registrations[entry.mutatorId] ?: return false
        val effects =
            try {
                normalizedMutationEffects(registration.stales(key, entry.args))
            } catch (failure: Throwable) {
                poisonSink.tryEmit(
                    PoisonedIntent(
                        mutationId = entry.mutationId,
                        mutatorId = entry.mutatorId,
                        failure = failure,
                    ),
                )
                return false
            }
        effectSnapshots[entry.mutationId] = effects
        return true
    }

    /** The captured (never executed) effect snapshot for a mutation; 022 owns durability. */
    internal fun capturedEffectsSnapshot(mutationId: String): List<MutationEffectRecord>? =
        effectSnapshots[mutationId]

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

/** One completed present adoption: the adopted value and the effective (possibly rekeyed) key. */
private class PresentAdoption<K : StoreKey, V : Any>(
    val adopted: V,
    val effectiveKey: K,
)
