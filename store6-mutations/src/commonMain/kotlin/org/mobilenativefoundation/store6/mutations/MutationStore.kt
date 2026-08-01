@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.store
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.KeyEvents
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.StoreResults
import org.mobilenativefoundation.store6.core.seam.runtime

/**
 * Narrows a [Store] to journalled mutation writes while preserving Store reads and maintenance,
 * and routes every key-taking operation through the canonical alias table (D15a).
 *
 * PROVISIONAL pending Issue 021: this facade deliberately withholds the raw engine write handle.
 * Calling `runtime()` on it returns `null`; [keyEvents] is re-published so advisory access survives
 * that narrowing, and it gains no `Rekeyed` variant. [close] marks the facade closed before
 * closing its delegate, and mutation operations then fail with Store's exact closed-store
 * contract.
 *
 * Canonical routing (D14/D15a): every key-taking method resolves the terminal identity of its
 * key before delegating — a key whose identity no active alias redirects IS terminal and is used
 * as given, without consulting the resolver. For an aliased identity the retained
 * [MutationKeyResolver] reconstructs the canonical `K` (exact-pair validated); the suspending
 * methods make ONE resolution attempt and throw a `StoreResults.conversionError`-backed
 * [StoreException] on failure, while [stream] emits the sanctioned conversion error and stays
 * live. Raw [Store] references and foreign graphs do not follow the alias guarantee.
 */
@ExperimentalStoreApi
public class MutationStore<K : StoreKey, V : Any> internal constructor(
    private val delegate: Store<K, V>,
    private val engine: MutationEngine<K, V>,

    /** The exact advisory event flow captured from the delegate runtime during construction. */
    @ExperimentalStoreApi
    public val keyEvents: Flow<KeyEvents>,
) : Store<K, V> by delegate {
    private val closed = MutableStateFlow(false)
    private val drainPass = Mutex()

    /**
     * Observes retrieval state and values for the terminal canonical identity of [key] (D15a).
     *
     * Alias liveness contract (D14): before resolving, the stream snapshots the mutation-owned
     * stateful revision for the terminal identity. On resolver null, throw, or identity mismatch
     * it emits exactly one `StoreResults.error(StoreResults.conversionError(message, cause),
     * servedStale = false)`, never delegates to the stale source key, never completes, and
     * suspends until a strictly newer revision for that identity — a later alias activation or a
     * non-stream facade resolution attempt — then retries; its own attempt never advances the
     * revision, and a new collection attempts resolution immediately. On success it collects the
     * terminal delegate stream and, after a later activation redirects the identity, re-resolves
     * and swaps to the new canonical delegate stream. [close] cancels a waiting collector
     * promptly and releases its retry subscription.
     */
    override fun stream(
        key: K,
        freshness: Freshness,
    ): Flow<StoreResult<V>> =
        flow {
            var firstAttempt = true
            while (true) {
                if (closed.value) {
                    if (firstAttempt) throw IllegalStateException("Store is closed.")
                    throw CancellationException("Store is closed.")
                }
                firstAttempt = false
                val terminal = engine.terminalIdentityOf(key.identity())
                val aliasRevisions = engine.aliasRevision(terminal)
                val resolutionPulses = engine.resolutionPulse(terminal)
                // D14: snapshot the mutation-owned stateful signals BEFORE resolving so an
                // activation or facade attempt landing mid-resolution can never be lost.
                val aliasSnapshot = aliasRevisions.value
                val pulseSnapshot = resolutionPulses.value
                val resolution = engine.resolveTerminalKey(key)
                if (resolution.identity != terminal) {
                    // An activation moved the terminal between the snapshot and the attempt;
                    // the snapshots guard the wrong identity, so re-attempt immediately.
                    continue
                }
                when (resolution) {
                    is TerminalKeyResolution.Failed -> {
                        emit(
                            StoreResults.error(
                                StoreResults.conversionError(
                                    resolution.message,
                                    resolution.cause,
                                ),
                                servedStale = false,
                            ),
                        )
                        val woke =
                            merge(
                                resolutionPulses
                                    .filter { revision -> revision > pulseSnapshot }
                                    .map { true },
                                closed.filter { it }.map { false },
                            ).first()
                        if (!woke) throw CancellationException("Store is closed.")
                    }
                    is TerminalKeyResolution.Resolved -> {
                        var completedNormally = false
                        var rerouted = false
                        val guarded =
                            merge(
                                delegate
                                    .stream(resolution.key, freshness)
                                    .map<StoreResult<V>, FacadeStreamElement<V>> { result ->
                                        FacadeStreamElement.Emission(result)
                                    }
                                    .onCompletion { failure ->
                                        if (failure == null) {
                                            emit(FacadeStreamElement.Completed)
                                        }
                                    },
                                aliasRevisions
                                    .filter { revision -> revision > aliasSnapshot }
                                    .map<Long, FacadeStreamElement<V>> {
                                        FacadeStreamElement.Rerouted
                                    },
                            )
                        emitAll(
                            guarded.transformWhile { element ->
                                when (element) {
                                    is FacadeStreamElement.Emission -> {
                                        emit(element.result)
                                        true
                                    }
                                    FacadeStreamElement.Completed -> {
                                        completedNormally = true
                                        false
                                    }
                                    FacadeStreamElement.Rerouted -> {
                                        rerouted = true
                                        false
                                    }
                                }
                            },
                        )
                        if (completedNormally) return@flow
                        check(rerouted) {
                            "Alias-guarded stream stopped without completion or reroute."
                        }
                        // Rerouted: loop, re-resolve the strictly newer terminal, and swap to
                        // the canonical delegate stream.
                    }
                }
            }
        }

    /**
     * Returns the value for the terminal canonical identity of [key] (D15a); one resolution
     * attempt, throwing a `StoreResults.conversionError`-backed [StoreException] on failure
     * (D14). This read is never projected by the overlay; overlays apply only to [stream].
     */
    override suspend fun get(
        key: K,
        freshness: Freshness,
    ): V {
        checkOpen()
        return delegate.get(engine.requireTerminalKey(key), freshness)
    }

    /**
     * Marks the terminal canonical identity of [key] stale (D15a); one resolution attempt,
     * throwing a `StoreResults.conversionError`-backed [StoreException] on failure (D14).
     */
    override suspend fun invalidate(key: K) {
        checkOpen()
        delegate.invalidate(engine.requireTerminalKey(key))
    }

    /**
     * Destructively removes the value for the terminal canonical identity of [key] (D15a); one
     * resolution attempt, throwing a `StoreResults.conversionError`-backed [StoreException] on
     * failure (D14).
     */
    override suspend fun clear(key: K) {
        checkOpen()
        delegate.clear(engine.requireTerminalKey(key))
    }

    // invalidateNamespace, invalidateAll, clearNamespace, and clearAll need no key and delegate
    // directly through `Store by delegate` (D14: namespace/all operations delegate directly).

    /**
     * Appends one typed intent to the journal and signals optimistic reprojection.
     *
     * The terminal canonical identity is resolved BEFORE the append (D14/D15a): the intent is
     * journalled at the effective identity so queued siblings merge by durable client sequence,
     * and a resolution failure throws the sanctioned `StoreResults.conversionError`-backed
     * [StoreException] without creating any intent.
     */
    @ExperimentalStoreApi
    public suspend fun <A : Any> mutate(
        key: K,
        ref: MutatorRef<K, V, A>,
        args: A,
    ): String {
        checkOpen()
        return engine.mutate(engine.requireTerminalKey(key), ref, args)
    }

    /**
     * Runs one idempotent, scheduler-agnostic foreground pass for the terminal canonical
     * identity of [key] (D12/D15a): the engine captures the unprojected confirmed base through
     * the ordered `status -> LocalOnly` loop and pushes the pending FIFO prefix once, with no
     * retry, backoff, or parking. This pass never fetches. Push failure stops normally with the
     * current intent pending; adoption failure propagates; an alias-protocol violation records a
     * normalized `PROTOCOL` carrier and stops normally (023 owns the durable park transition).
     * Terminal resolution makes one attempt and throws the sanctioned conversion-backed
     * [StoreException] on failure. Drain passes are serialized store-wide; Issue 023 owns
     * per-key scheduling and parallelism.
     */
    @ExperimentalStoreApi
    public suspend fun drain(key: K) {
        drainPass.withLock {
            checkOpen()
            engine.drainKeyResolvedOrRecord(key)
        }
    }

    /**
     * Runs one idempotent, scheduler-agnostic global foreground pass (D12): every durable
     * identity is enumerated from the journal and reconstructed through the required
     * [MutationKeyResolver] with exact-pair validation (D14). An identity that fails to resolve
     * does not block the others. Global processing is deterministic by durable client sequence
     * within an effective identity and does not promise cross-key order. Drain passes are
     * serialized store-wide; Issue 023 owns scheduling and checkpoint flushing.
     */
    @ExperimentalStoreApi
    public suspend fun drain() {
        drainPass.withLock {
            checkOpen()
            engine.drain()
        }
    }

    /**
     * Returns the current pending intents for the terminal canonical identity of [key] in
     * durable client-sequence FIFO order.
     *
     * Aliases are followed as durable identity pairs only (D14): this inspection never
     * reconstructs a `K`, never consults the resolver, and therefore cannot fail on an
     * unresolvable canonical key.
     */
    @ExperimentalStoreApi
    public suspend fun pending(key: K): List<PendingIntent> {
        checkOpen()
        return engine.pendingForIdentity(engine.terminalIdentityOf(key.identity()))
    }

    /**
     * Returns a truthful snapshot of every nonterminal active intent across all durable
     * identities, in durable client-sequence order (D3). Retired history never appears.
     */
    @ExperimentalStoreApi
    public suspend fun pendingWrites(): List<PendingIntent> {
        checkOpen()
        return engine.pendingWrites()
    }

    /**
     * Returns the durably parked intents (D3). Dead letters contain only `PARKED` entries;
     * retired history never appears and post-acknowledgement work never parks. At 021 this list
     * is always empty: parking is produced by Issue 023 over Issue 022's durable rows.
     */
    @ExperimentalStoreApi
    public suspend fun deadLetters(): List<DeadLetter> {
        checkOpen()
        return engine.deadLetters()
    }

    /** The replay-buffered projection-failure containment stream owned by the mutation engine. */
    @ExperimentalStoreApi
    public val poisoned: SharedFlow<PoisonedIntent>
        get() = engine.poisoned

    /**
     * Read-only, in-process advisory lifecycle events (D4): replay `0`, extra buffer capacity
     * `64`, overflow `DROP_OLDEST`, non-blocking emission. Never a drain, acknowledgement, retry,
     * or settlement protocol; durable truth remains inspection. Issue 023 owns causal emission.
     */
    @ExperimentalStoreApi
    public val events: SharedFlow<MutationEvent>
        get() = engine.eventBus.events

    /** The exact Bookkeeper the engine retained (D9); test/022/024 verification door. */
    internal val bookkeeperRetainedByEngine: Bookkeeper
        get() = engine.bookkeeper

    /** The exact SourceOfTruth the engine retained (D9); test/022/024 verification door. */
    internal val sourceOfTruthRetainedByEngine: SourceOfTruth<K, V>
        get() = engine.sourceOfTruth

    override fun close() {
        // The stateful transition wakes any stream suspended on a resolver-retry subscription
        // (D14): the waiter observes the closed signal, cancels promptly, and its `first`
        // releases the retry subscription.
        closed.value = true
        delegate.close()
    }

    private fun checkOpen() {
        if (closed.value) {
            throw IllegalStateException("Store is closed.")
        }
    }
}

/** One element of the alias-guarded facade stream: an emission, completion, or reroute marker. */
private sealed interface FacadeStreamElement<out V> {
    class Emission<V>(
        val result: StoreResult<V>,
    ) : FacadeStreamElement<V>

    object Completed : FacadeStreamElement<Nothing>

    object Rerouted : FacadeStreamElement<Nothing>
}

/**
 * Builds a Store whose sole overlay is the mutation engine and returns its narrowed facade.
 *
 * The ruled entry point (D1): restart behavior is compile-time required — the registry, server,
 * key resolver, and value codec/version are factory inputs, and optional Store configuration
 * lives on [MutationStoreBuilder], which exposes no overlay door. The retained
 * [SourceOfTruth]/[Bookkeeper] selections (or mutations-owned defaults) are installed in the
 * delegated Store AND retained by the engine, so Issue 024's transactional decorator can select
 * and report its path instead of silently discovering an inaccessible core default (D9). The
 * delegate runtime is captured exactly once, its write handle is bound privately, and neither
 * runtime nor handle is exposed. The engine's confirmed-base reader and confirmed-absence
 * adoption door close over the delegate through the same construction cycle the write handle
 * uses: the overlay must be installed at build time, so both lambdas are bound before the
 * delegate exists and first run only after it does.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> mutationStore(
    registry: MutatorRegistry<K, V>,
    server: MutationServer<K, V>,
    keyResolver: MutationKeyResolver<K>,
    valueCodecVersion: Int,
    valueCodec: MutationCodec<V>,
    configure: MutationStoreBuilder<K, V>.() -> Unit,
): MutationStore<K, V> {
    require(valueCodecVersion >= 1) {
        "valueCodecVersion must be positive."
    }
    val configuration = MutationStoreBuilder<K, V>().apply(configure).snapshot()
    var boundDelegate: Store<K, V>? = null
    val engine =
        MutationEngine(
            registry = registry,
            server = server,
            keyResolver = keyResolver,
            valueCodecVersion = valueCodecVersion,
            valueCodec = valueCodec,
            conflicts = configuration.conflicts,
            bookkeeper = configuration.bookkeeper,
            sourceOfTruth = configuration.sourceOfTruth,
            baseReader = { key -> checkNotNull(boundDelegate).confirmedBaseOrNull(key) },
            absentAdoption = { key -> checkNotNull(boundDelegate).clear(key) },
            wallClock = configuration.wallClock ?: MutationsSystemWallClock,
        )
    val delegate =
        store<K, V> {
            configuration.applyCoreConfiguration(this)
            overlay(engine.overlay)
        }
    boundDelegate = delegate
    val runtime = checkNotNull(delegate.runtime())
    engine.bind(runtime.writeHandle)
    return MutationStore(
        delegate = delegate,
        engine = engine,
        keyEvents = runtime.keyEvents,
    )
}

/**
 * Reads the unprojected confirmed base with [Freshness.LocalOnly]; a missing value becomes
 * `null` for the engine's ordered capture loop. This read never fetches.
 */
private suspend fun <K : StoreKey, V : Any> Store<K, V>.confirmedBaseOrNull(key: K): V? =
    try {
        get(key, Freshness.LocalOnly)
    } catch (failure: StoreException) {
        if (failure.error is StoreError.Missing) {
            null
        } else {
            throw failure
        }
    }
