package org.mobilenativefoundation.store6.core.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreException
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.FetchPlan
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.FreshnessContext
import org.mobilenativefoundation.store6.core.seam.FreshnessValidator
import org.mobilenativefoundation.store6.core.seam.KeyEvents
import org.mobilenativefoundation.store6.core.seam.KeyStatus
import org.mobilenativefoundation.store6.core.seam.Overlay
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import org.mobilenativefoundation.store6.core.seam.WallClock
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Emits every stale epoch that advanced beyond the snapshot used to plan a stream startup. */
internal fun Flow<KeyState>.staleEpochsAfter(planningEpoch: Long): Flow<Long> =
    map { it.staleEpoch }
        .distinctUntilChanged()
        .filter { observedEpoch -> observedEpoch > planningEpoch }

/**
 * Coordinates one canonical key around a single shared source-of-truth reader pipeline.
 *
 * [writeLock] serializes persistence mutations and ordered bookkeeping. [stateLock] protects the
 * immutable state snapshot, residence, and its monotone revision. When both locks are needed the
 * order is always writeLock then stateLock; no lock is held across fetcher I/O.
 */
@OptIn(ExperimentalStoreApi::class, ExperimentalCoroutinesApi::class)
internal class KeyEngine<K : StoreKey, V : Any>(
    internal val key: K,
    private val keyId: KeyId,
    private val fetcher: Fetcher<K, V>,
    private val sot: SourceOfTruth<K, V>,
    private val bookkeeper: Bookkeeper,
    private val validator: FreshnessValidator,
    private val wallClock: WallClock,
    private val engineScope: CoroutineScope,
    private val residencyHooks: EngineResidencyHooks = EngineResidencyHooks.Noop,
    /** Optional deterministic gate used only by direct engine tests before final initial recapture. */
    private val beforeInitialDeliveryTestGate: suspend () -> Unit = {},
    /** Optional direct-test gate after the initial planning snapshot, before outcome classification. */
    private val afterInitialPlanningSnapshotTestGate: suspend () -> Unit = {},
    /** Optional deterministic gate used only by direct engine tests after raw reader observation. */
    private val beforeReaderRecordMappingTestGate: suspend () -> Unit = {},
    /** Optional direct-test gate after mapping but before serialized reader delivery. */
    private val beforeReaderDeliveryLockTestGate: suspend (ReaderRecord<V>) -> Unit = {},
    /** Optional deterministic gate used only by direct engine tests inside serialized delivery. */
    private val beforeReaderDeliveryTestGate: suspend () -> Unit = {},
    /** Optional deterministic gate used only by direct engine tests before outcome delivery. */
    private val beforeTicketOutcomeDeliveryTestGate: suspend () -> Unit = {},
    /** Optional deterministic gate before first classification of a replacement disposition. */
    private val beforeReplacementDispositionClassificationTestGate: suspend () -> Unit = {},
    /** Store-local fence shared by RealStore; direct tests receive an isolated coordinator. */
    private val maintenanceCoordinator: MaintenanceCoordinator = MaintenanceCoordinator(),
    /** Null keeps every telemetry hook and fetch-duration allocation off the unconfigured path. */
    private val telemetry: StoreTelemetry? = null,
    /** Store-level advisory bus; direct engine tests receive an isolated equivalent by default. */
    private val events: MutableSharedFlow<KeyEvents> =
        MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        ),
    /** Null preserves the direct-residence path without projection allocations. */
    private val overlay: Overlay<K, V>? = null,
    /** Deterministic direct-test gate after Pending and before invoking Overlay.apply. */
    private val beforeProjectionApplyTestGate: suspend (V?) -> Unit = {},
    /** Deterministic direct-test gate after Overlay.apply and before the commit recheck. */
    private val afterProjectionApplyTestGate: suspend (V?) -> Unit = {},
    /** Deterministic direct-test gate before serialized projection snapshot delivery. */
    private val beforeProjectionDeliveryLockTestGate: suspend () -> Unit = {},
    /** Deterministic direct-test gate inside serialized projection snapshot delivery. */
    private val beforeProjectionDeliveryTestGate: suspend () -> Unit = {},
    /** Deterministic direct-test gate after serialized projection snapshot delivery. */
    private val afterProjectionDeliveryTestGate: suspend () -> Unit = {},
    /** Deterministic direct-test gate before a readiness waiter suspends on both state flows. */
    private val beforeProjectionReadinessWaitTestGate: suspend () -> Unit = {},
    /** Deterministic direct-test gate after coherent base capture and before authorization. */
    private val beforeProjectionAuthorizationTestGate: suspend () -> Unit = {},
) {
    private val stateLock = Mutex()
    private val writeLock = Mutex()
    private val engineJob: Job = checkNotNull(engineScope.coroutineContext[Job])
    private val closeSignal: Job = Job(engineJob)

    private val mutableState = MutableStateFlow(KeyState.Initial)
    internal val state: StateFlow<KeyState> = mutableState.asStateFlow()

    private val residence = MutableStateFlow<ValueEnvelope<V>?>(null)

    /** Changed only by [replaceResidenceLocked] while stateLock is held. */
    private var residenceRevision: Long = 0L

    /** Exists only for configured engines and immediately obsoletes revision-bound waiters. */
    private val projectionResidence: MutableStateFlow<ProjectionResidence<V>>? =
        overlay?.let {
            MutableStateFlow(
                ProjectionResidence(
                    ProjectionBase(
                        envelope = null,
                        revision = residenceRevision,
                    ),
                ),
            )
        }

    /** Latest single-writer state; null is the allocation-free unconfigured path. */
    private val projectionSnapshot: MutableStateFlow<ProjectionSnapshot<V>>? =
        overlay?.let { MutableStateFlow(ProjectionSnapshot.Uninitialized) }

    /** Advanced only by the projection writer while publishing Pending or Terminal. */
    private var projectionGeneration: Long = 0L

    /** Lock-serialized source-order boundary for raw observations and active SoT writes. */
    private val writeObservationBoundary =
        MutableStateFlow(
            WriteObservationBoundary(
                readerGen = 0L,
                observedAttribution = null,
                activeAttribution = null,
                successfulSequence = 0L,
                latestRawSequence = 0L,
                activeRawPhase = ActiveRawPhase.Unobserved,
                readerSession = 0L,
                readerSessionActive = false,
                pendingWriteAttribution = null,
            ),
        )

    /** Latest durable resolution of raw observations ordered before a successful write return. */
    private var rawCommitResolution: RawCommitResolution<V>? = null

    /** Completion fence for a destructive persistence mutation; guarded by [stateLock]. */
    private var destructiveMutationBarrier: CompletableDeferred<Unit>? = null

    /**
     * One retrying adapter pipeline shared by every active collector for this key.
     *
     * Adapter invocation and collection failures are converted before engine mapping. A mapping
     * or transition defect therefore remains fatal instead of being mislabeled and retried as a
     * persistence outage.
     */
    private val readerRecords: SharedFlow<ReaderRecord<V>> =
        state
            .map { it.readerGen }
            .distinctUntilChanged()
            .flatMapLatest { readerGen ->
                var failureReportedForEpisode = false
                flow {
                    val readerSession = beginRawReaderSession(readerGen)
                    try {
                        emitAll(sot.reader(key))
                    } finally {
                        endRawReaderSession(readerGen, readerSession)
                    }
                }
                    .map<V?, RawReaderEvent<V>> { value ->
                        failureReportedForEpisode = false
                        try {
                            rawReaderRow(readerGen, value)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Throwable) {
                            throw RawObservationFailure(failure)
                        }
                    }
                    .conflate()
                    .onCompletion { cause ->
                        if (cause == null) {
                            error(
                                "SourceOfTruth.reader completed normally for key " +
                                    "'${keyId.namespace}/${keyId.canonicalId}'.",
                            )
                        }
                    }
                    .retryWhen { failure, _ ->
                        if (failure is CancellationException) throw failure
                        if (failure is RawObservationFailure) throw failure.engineFailure
                        if (!failureReportedForEpisode) {
                            emit(RawReaderEvent.Failure(readerException(failure)))
                            failureReportedForEpisode = true
                        }
                        delay(READER_RETRY_DELAY_MILLIS)
                        true
                    }
                    .mapNotNull { event ->
                        when (event) {
                            is RawReaderEvent.Row -> {
                                beforeReaderRecordMappingTestGate()
                                toRecord(readerGen, event)
                            }
                            is RawReaderEvent.Failure ->
                                readerFailureRecord(readerGen, event.exception)
                        }
                    }
                    .retryWhen { failure, _ ->
                        if (failure is RestartRawReaderSession) {
                            true
                        } else {
                            throw failure
                        }
                    }
            }
            .shareIn(
                scope = engineScope,
                started =
                    SharingStarted.WhileSubscribed(
                        stopTimeoutMillis = READER_PIPELINE_GRACE_MILLIS,
                        replayExpirationMillis = 0L,
                    ),
                replay = 1,
            )

    init {
        overlay?.let { configured ->
            engineScope.launch { runProjectionWriter(configured) }
        }
    }

    /** Quiescent for idling: no fetch owns the slot. All other work holds registry references. */
    internal fun isQuiescentForIdle(): Boolean = state.value.fetch is FetchSlot.Idle

    /** Destroys a quiescent, unreferenced engine; nothing user-visible is running by definition. */
    internal fun destroy() {
        engineScope.cancel(CancellationException(ENGINE_EVICTED_MESSAGE))
    }

    /** Assigns residence and advances its revision for every accepted observation or mutation. */
    private fun replaceResidenceLocked(
        envelope: ValueEnvelope<V>?,
        preserveProjectionAuthorizationLineage: Boolean = false,
    ): Long {
        val nextProjectionAuthorizationLineage =
            projectionResidence?.let { projection ->
                when {
                    envelope == null -> null
                    preserveProjectionAuthorizationLineage ->
                        checkNotNull(projection.value.base.authorizationLineage) {
                            "A metadata successor requires an existing projection lineage."
                        }
                    else -> ProjectionAuthorizationLineage()
                }
            }
        residence.value = envelope
        residenceRevision += 1L
        projectionResidence?.value =
            ProjectionResidence(
                ProjectionBase(
                    envelope = envelope,
                    revision = residenceRevision,
                    authorizationLineage = nextProjectionAuthorizationLineage,
                ),
            )
        return residenceRevision
    }

    /** Returns the configured projection base only when it names this exact residence snapshot. */
    private fun projectionBaseLocked(
        envelope: ValueEnvelope<V>?,
        revision: Long,
    ): ProjectionBase<V>? =
        projectionResidence?.value?.base?.takeIf { base ->
            base.envelope === envelope && base.revision == revision
        }

    /** Serially accepts residence and overlay triggers and computes outside every Store lock. */
    private suspend fun runProjectionWriter(configured: Overlay<K, V>) {
        val residenceTriggers = checkNotNull(projectionResidence).map { Unit }
        val overlayTriggers =
            configured.changes
                .filter { changed -> KeyId.from(changed) == keyId }
                .map { Unit }
                .catch { failure ->
                    // Downstream/parent cancellation stays transparent, preserving an apply failure.
                    if (failure is CancellationException && engineJob.isActive) {
                        throw ProjectionChangesFailure(failure)
                    }
                    throw failure
                }
        try {
            merge(residenceTriggers, overlayTriggers).collect {
                val pending =
                    stateLock.withLock {
                        val base = checkNotNull(projectionResidence).value.base
                        projectionGeneration += 1L
                        ProjectionSnapshot.Pending(
                            base = base,
                            generation = projectionGeneration,
                        ).also { projection ->
                            checkNotNull(projectionSnapshot).value = projection
                        }
                    }
                val baseValue = pending.base.envelope?.value
                beforeProjectionApplyTestGate(baseValue)
                val output = configured.apply(key, baseValue)
                afterProjectionApplyTestGate(baseValue)
                val projection =
                    when {
                        pending.base.envelope != null &&
                            output == pending.base.envelope.value ->
                            Projection.Value(pending.base.envelope)

                        output == null -> Projection.Absent
                        else -> Projection.Overlaid(output)
                    }
                stateLock.withLock {
                    val currentResidence = checkNotNull(projectionResidence).value.base
                    val currentSnapshot = checkNotNull(projectionSnapshot).value
                    if (
                        currentResidence.matches(pending.base) &&
                        currentSnapshot is ProjectionSnapshot.Pending &&
                        currentSnapshot.generation == pending.generation
                    ) {
                        projectionSnapshot.value =
                            ProjectionSnapshot.Ready(
                                base = pending.base,
                                generation = pending.generation,
                                projection = projection,
                            )
                    }
                }
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException && !engineJob.isActive) throw failure
            val terminalFailure =
                (failure as? ProjectionChangesFailure)?.projectionCause ?: failure
            stateLock.withLock {
                projectionGeneration += 1L
                checkNotNull(projectionSnapshot).value =
                    ProjectionSnapshot.Terminal(
                        generation = projectionGeneration,
                        failure = terminalFailure,
                    )
            }
        }
    }

    /** Opens one upstream reader session and retires any fence from a cancelled predecessor. */
    private fun beginRawReaderSession(readerGen: Long): Long {
        val opened =
            updateWriteObservationBoundary { current ->
                if (current.readerGen != readerGen) {
                    current
                } else {
                    val nextSession = current.readerSession + 1L
                    current.copy(
                        readerSession = nextSession,
                        readerSessionActive = true,
                        pendingWriteAttribution = null,
                    )
                }
            }
        return opened.readerSession
    }

    /** Retires only the matching session; a newer reader must keep its own boundary state. */
    private fun endRawReaderSession(
        readerGen: Long,
        readerSession: Long,
    ) {
        updateWriteObservationBoundary { current ->
            if (current.readerGen == readerGen && current.readerSession == readerSession) {
                current.copy(
                    readerSessionActive = false,
                    pendingWriteAttribution = null,
                )
            } else {
                current
            }
        }
    }

    /** Captures source order and active-write provenance under [stateLock] before conflation. */
    private suspend fun rawReaderRow(
        readerGen: Long,
        value: V?,
    ): RawReaderEvent.Row<V> =
        stateLock.withLock { captureRawReaderRowLocked(readerGen, value) }

    /** Allocates one raw token; the return-boundary CAS may race but mapping cannot. */
    private fun captureRawReaderRowLocked(
        readerGen: Long,
        value: V?,
    ): RawReaderEvent.Row<V> {
        while (true) {
            val current = writeObservationBoundary.value
            if (current.readerGen != readerGen) {
                return RawReaderEvent.Row(
                    value = value,
                    readerGen = readerGen,
                    rawObservationSequence = current.latestRawSequence,
                    attributionAtObservation = current.observedAttribution,
                    successfulWriteSequenceAtObservation = current.successfulSequence,
                    activeWriteAttributionAtObservation = current.activeAttribution,
                    followedMatchingActiveWriteRow = false,
                    pendingCommitFenceAtObservation = false,
                )
            }

            val nextSequence = current.latestRawSequence + 1L
            // A live reader can have pre-return notifications queued upstream. The exact
            // writer-current closes that fence; observations after it are later authority.
            val pendingWriteAttribution = current.pendingWriteAttribution
            val activeWriteAttribution = current.activeAttribution
            val activeAttributionAtObservation =
                when {
                    pendingWriteAttribution == null -> activeWriteAttribution
                    value != null && pendingWriteAttribution.value == value ->
                        pendingWriteAttribution
                    value != null && activeWriteAttribution?.value == value ->
                        activeWriteAttribution
                    else -> pendingWriteAttribution
                }
            val observation =
                RawWriteObservation(
                    readerGen = readerGen,
                    rawSequence = nextSequence,
                    value = value,
                    attributionAtObservation = current.observedAttribution,
                    activeWriteAttributionAtObservation = activeAttributionAtObservation,
                    successfulWriteSequenceAtObservation = current.successfulSequence,
                )
            val activeObservation =
                if (activeAttributionAtObservation === activeWriteAttribution) {
                    observation
                } else {
                    observation.copy(
                        activeWriteAttributionAtObservation = activeWriteAttribution,
                    )
                }
            val matchingActiveAttribution = activeObservation.matchingWriterAttribution()
            val followedMatchingActiveWriteRow =
                activeWriteAttribution != null &&
                    matchingActiveAttribution == null &&
                    (current.activeRawPhase is ActiveRawPhase.Matching ||
                        current.activeRawPhase is ActiveRawPhase.OtherAfterMatching)
            val nextPhase =
                if (activeWriteAttribution == null) {
                    current.activeRawPhase
                } else if (matchingActiveAttribution != null) {
                    ActiveRawPhase.Matching(activeObservation, matchingActiveAttribution)
                } else {
                    when (current.activeRawPhase) {
                        is ActiveRawPhase.Matching ->
                            ActiveRawPhase.OtherAfterMatching(
                                matchingObservation = current.activeRawPhase.observation,
                                observation = activeObservation,
                            )

                        is ActiveRawPhase.OtherAfterMatching ->
                            ActiveRawPhase.OtherAfterMatching(
                                matchingObservation =
                                    current.activeRawPhase.matchingObservation,
                                observation = activeObservation,
                            )

                        ActiveRawPhase.Unobserved,
                        is ActiveRawPhase.OtherBeforeMatching,
                        -> ActiveRawPhase.OtherBeforeMatching(activeObservation)
                    }
                }
            val activeExactSupersedesPending =
                value != null &&
                    activeWriteAttribution != null &&
                    activeWriteAttribution.value == value
            val updated =
                current.copy(
                    latestRawSequence = nextSequence,
                    activeRawPhase = nextPhase,
                    pendingWriteAttribution =
                        if (
                            value != null &&
                            (pendingWriteAttribution?.value == value ||
                                activeExactSupersedesPending)
                        ) {
                            null
                        } else {
                            pendingWriteAttribution
                        },
                )
            if (writeObservationBoundary.compareAndSet(current, updated)) {
                return RawReaderEvent.Row(
                    value = value,
                    readerGen = readerGen,
                    rawObservationSequence = nextSequence,
                    attributionAtObservation = observation.attributionAtObservation,
                    successfulWriteSequenceAtObservation =
                        observation.successfulWriteSequenceAtObservation,
                    activeWriteAttributionAtObservation =
                        observation.activeWriteAttributionAtObservation,
                    followedMatchingActiveWriteRow = followedMatchingActiveWriteRow,
                    pendingCommitFenceAtObservation =
                        pendingWriteAttribution != null &&
                            activeAttributionAtObservation === pendingWriteAttribution,
                )
            }
        }
    }

    /** Mirrors lock-owned state into the source-order boundary while [stateLock] is held. */
    private fun syncObservedAttributionLocked(state: KeyState) {
        val generationChanged = writeObservationBoundary.value.readerGen != state.readerGen
        if (generationChanged) {
            rawCommitResolution = null
        }
        updateWriteObservationBoundary { current ->
            if (current.readerGen == state.readerGen) {
                current.copy(observedAttribution = state.attribution)
            } else {
                current.copy(
                    readerGen = state.readerGen,
                    observedAttribution = state.attribution,
                    activeAttribution = null,
                    activeRawPhase = ActiveRawPhase.Unobserved,
                    readerSessionActive = false,
                    pendingWriteAttribution = null,
                )
            }
        }
    }

    /** Applies one CAS-loop boundary update and preserves concurrent raw observations. */
    private inline fun updateWriteObservationBoundary(
        transform: (WriteObservationBoundary) -> WriteObservationBoundary,
    ): WriteObservationBoundary {
        while (true) {
            val current = writeObservationBoundary.value
            val updated = transform(current)
            if (writeObservationBoundary.compareAndSet(current, updated)) return updated
        }
    }

    /** Applies one pure event while serializing the state swap. */
    private suspend fun applyEvent(event: KeyEvent): KeyEffect =
        stateLock.withLock {
            val result = transition(mutableState.value, event)
            mutableState.value = result.state
            syncObservedAttributionLocked(result.state)
            result.effect
        }

    /** Maps a reader row only after any exact writer attribution becomes durably committed. */
    private suspend fun toRecord(
        readerGen: Long,
        event: RawReaderEvent.Row<V>,
    ): ReaderRecord<V>? {
        var prepared: PreparedReaderRow? = null
        var decided = false
        val immediate =
            stateLock.withLock {
                val snapshot = mutableState.value
                if (snapshot.readerGen != readerGen) return@withLock null
                if (isSupersededRawObservation(event)) {
                    decided = true
                    return@withLock null
                }
                rawCommitResolution
                    ?.takeIf {
                        it.readerGen == readerGen &&
                            event.rawObservationSequence <= it.rawCommitCutoff
                    }
                    ?.let { resolution ->
                        decided = true
                        return@withLock if (
                            event.rawObservationSequence ==
                            resolution.authoritativeRawSequence
                        ) {
                            recordFromConvergedRawLocked(event, resolution)
                                ?: recordForConfirmFreshAdvancedEnvelopeLocked(
                                    event = event,
                                    resolution = resolution,
                                    consumedAttributionOverride = null,
                                )
                        } else {
                            null
                        }
                    }
                // A fenced mismatch is ambiguous with a notification queued before the
                // writer-current. It cannot map directly; a committed owner replaces the reader
                // so that only the new session's current row can establish later authority.
                if (event.pendingCommitFenceAtObservation) {
                    val ownerAttribution =
                        checkNotNull(event.activeWriteAttributionAtObservation)
                    val matchingAttribution =
                        ownerAttribution.takeIf {
                            event.value != null && it.value == event.value
                        }
                    when (val disposition = ownerAttribution.owner.disposition.value) {
                        is FetchDisposition.Committed -> {
                            decided = true
                            return@withLock if (
                                matchingAttribution != null &&
                                disposition.attribution === ownerAttribution
                            ) {
                                recordForExactWriterEnvelopeLocked(
                                    event = event,
                                    attribution = ownerAttribution,
                                    consumedAttribution = null,
                                ) ?: recordForCurrentSameValueEnvelopeLocked(
                                    event = event,
                                    consumedAttribution = null,
                                )
                            } else {
                                if (
                                    matchingAttribution == null &&
                                    disposition.attribution === ownerAttribution &&
                                    pendingFenceStillActiveLocked(event, ownerAttribution)
                                ) {
                                    throw RestartRawReaderSession()
                                }
                                null
                            }
                        }

                        FetchDisposition.InFlight,
                        is FetchDisposition.Committing,
                        -> {
                            prepared =
                                PreparedReaderRow(
                                    consumedAttribution = null,
                                    ownerAttribution = ownerAttribution,
                                    matchingAttribution = matchingAttribution,
                                    dropNonmatchingOnCommit = true,
                                )
                            return@withLock null
                        }

                        else -> {
                            decided = true
                            return@withLock null
                        }
                    }
                }

                val consumed =
                    transition(
                        snapshot,
                        KeyEvent.ConsumeAttribution(event.attributionAtObservation),
                    )
                mutableState.value = consumed.state
                syncObservedAttributionLocked(consumed.state)
                val tag = (consumed.effect as KeyEffect.Consumed).tag
                val value = event.value
                val matchingAttribution =
                    value?.let {
                        when {
                            tag?.value == value -> tag
                            tag == null &&
                                event.activeWriteAttributionAtObservation?.value == value ->
                                event.activeWriteAttributionAtObservation
                            else -> null
                        }
                    }
                val activeAttribution = event.activeWriteAttributionAtObservation
                val activeDisposition = activeAttribution?.owner?.disposition?.value
                val postReturnOrPostMatchProvisional =
                    matchingAttribution == null &&
                        activeDisposition is FetchDisposition.Committing &&
                        activeDisposition.attribution === activeAttribution &&
                        (event.successfulWriteSequenceAtObservation >
                            activeDisposition.successfulWriteSequenceAtStart ||
                            event.followedMatchingActiveWriteRow)
                if (postReturnOrPostMatchProvisional) {
                    prepared =
                        PreparedReaderRow(
                            consumedAttribution = tag,
                            ownerAttribution = checkNotNull(activeAttribution),
                            matchingAttribution = null,
                            dropNonmatchingOnCommit = false,
                        )
                    null
                } else if (matchingAttribution == null) {
                    decided = true
                    mapReaderRowLocked(
                        readerGen = readerGen,
                        event = event,
                        tag = tag,
                        matchingAttribution = null,
                    )
                } else {
                    when (val disposition = matchingAttribution.owner.disposition.value) {
                        is FetchDisposition.Committed -> {
                            decided = true
                            if (disposition.attribution !== matchingAttribution) {
                                null
                            } else {
                                recordForExactWriterEnvelopeLocked(
                                    event = event,
                                    attribution = matchingAttribution,
                                    consumedAttribution = tag,
                                )
                            }
                        }

                        FetchDisposition.InFlight,
                        is FetchDisposition.Committing,
                        -> {
                            prepared =
                                PreparedReaderRow(
                                    consumedAttribution = tag,
                                    ownerAttribution = matchingAttribution,
                                    matchingAttribution = matchingAttribution,
                                    dropNonmatchingOnCommit = false,
                                )
                            null
                        }

                        else -> {
                            decided = true
                            null
                        }
                    }
                }
            }
        if (decided) return immediate
        val provisionalRow = prepared ?: return immediate

        val ownerAttribution = provisionalRow.ownerAttribution
        val matchingAttribution = provisionalRow.matchingAttribution
        val owner = ownerAttribution.owner
        val disposition =
            when (val current = owner.disposition.value) {
                FetchDisposition.InFlight,
                is FetchDisposition.Committing,
                ->
                    owner.disposition.first { candidate ->
                        candidate !== FetchDisposition.InFlight &&
                            candidate !is FetchDisposition.Committing
                    }

                else -> current
            }
        val committed = disposition as? FetchDisposition.Committed
        if (committed != null && committed.attribution !== ownerAttribution) {
            return null
        }
        val restartFencedMismatch =
            committed != null &&
                provisionalRow.dropNonmatchingOnCommit &&
                matchingAttribution == null
        val writeDidNotCommit =
            disposition === FetchDisposition.Failed ||
                disposition === FetchDisposition.Cancelled
        if (committed == null && (!writeDidNotCommit || matchingAttribution != null)) return null
        // A terminal failed owner cannot lend its consumed tag to the resumed row. With no tag,
        // an equal live predecessor envelope stays unchanged while different content remains SOT.
        val retainedConsumedAttribution =
            provisionalRow.consumedAttribution.takeUnless { writeDidNotCommit }

        return stateLock.withLock {
            if (mutableState.value.readerGen != readerGen) return@withLock null
            if (isSupersededRawObservation(event)) return@withLock null
            rawCommitResolution
                ?.takeIf {
                    it.readerGen == readerGen &&
                        event.rawObservationSequence <= it.rawCommitCutoff
                }
                ?.let { resolution ->
                    return@withLock if (
                        event.rawObservationSequence == resolution.authoritativeRawSequence
                    ) {
                        recordFromConvergedRawLocked(
                            event = event,
                            resolution = resolution,
                            consumedAttributionOverride =
                                retainedConsumedAttribution,
                        ) ?: recordForConfirmFreshAdvancedEnvelopeLocked(
                            event = event,
                            resolution = resolution,
                            consumedAttributionOverride =
                                retainedConsumedAttribution,
                        )
                    } else {
                        null
                    }
                }
            if (restartFencedMismatch) {
                if (pendingFenceStillActiveLocked(event, ownerAttribution)) {
                    throw RestartRawReaderSession()
                }
                return@withLock null
            }
            if (matchingAttribution != null) {
                recordForExactWriterEnvelopeLocked(
                    event = event,
                    attribution = matchingAttribution,
                    consumedAttribution = retainedConsumedAttribution,
                ) ?: if (provisionalRow.dropNonmatchingOnCommit) {
                    recordForCurrentSameValueEnvelopeLocked(
                        event = event,
                        consumedAttribution = retainedConsumedAttribution,
                    )
                } else {
                    null
                }
            } else {
                mapReaderRowLocked(
                    readerGen = readerGen,
                    event = event,
                    tag = retainedConsumedAttribution,
                    matchingAttribution = null,
                )
            }
        }
    }

    /** True only while this event still belongs to the unresolved live-session fence. */
    private fun pendingFenceStillActiveLocked(
        event: RawReaderEvent.Row<V>,
        attribution: AttributionTag,
    ): Boolean {
        val boundary = writeObservationBoundary.value
        return boundary.readerGen == event.readerGen &&
            boundary.readerSessionActive &&
            boundary.pendingWriteAttribution === attribution
    }

    /** True when conflate has already observed a newer row/absence in this reader generation. */
    private fun isSupersededRawObservation(event: RawReaderEvent.Row<V>): Boolean {
        val boundary = writeObservationBoundary.value
        return boundary.readerGen == event.readerGen &&
            boundary.latestRawSequence > event.rawObservationSequence
    }

    /** Reuses commit-side convergence without mutating residence or advancing its revision. */
    private fun recordFromConvergedRawLocked(
        event: RawReaderEvent.Row<V>,
        resolution: RawCommitResolution<V>,
        consumedAttributionOverride: AttributionTag? = null,
    ): ReaderRecord<V>? {
        if (residenceRevision != resolution.residenceRevision) return null
        if (residence.value !== resolution.envelope) return null
        val value = event.value
        return if (value == null) {
            if (resolution.envelope != null) return null
            ReaderRecord.Absent(
                readerGen = event.readerGen,
                residenceRevision = residenceRevision,
                successfulWriteSequenceAtObservation =
                    event.successfulWriteSequenceAtObservation,
                consumedAttribution =
                    consumedAttributionOverride ?: resolution.consumedAttribution,
                activeWriteAttributionAtObservation =
                    event.activeWriteAttributionAtObservation,
                rawObservationSequence = event.rawObservationSequence,
            )
        } else {
            val envelope = resolution.envelope ?: return null
            if (envelope.value != value) return null
            ReaderRecord.Row(
                envelope = envelope,
                readerGen = event.readerGen,
                residenceRevision = residenceRevision,
                successfulWriteSequenceAtObservation =
                    event.successfulWriteSequenceAtObservation,
                consumedAttribution =
                    consumedAttributionOverride ?: resolution.consumedAttribution,
                activeWriteAttributionAtObservation =
                    event.activeWriteAttributionAtObservation,
                rawObservationSequence = event.rawObservationSequence,
            )
        }
    }

    /** Reuses only a confirmFresh-advanced envelope for the exact committed raw writer token. */
    private fun recordForConfirmFreshAdvancedEnvelopeLocked(
        event: RawReaderEvent.Row<V>,
        resolution: RawCommitResolution<V>,
        consumedAttributionOverride: AttributionTag?,
    ): ReaderRecord.Row<V>? {
        val value = event.value ?: return null
        val committedEnvelope = resolution.envelope ?: return null
        val currentEnvelope = residence.value ?: return null
        if (residenceRevision <= resolution.residenceRevision) return null
        if (currentEnvelope === committedEnvelope) return null

        val attribution = event.activeWriteAttributionAtObservation ?: return null
        val disposition =
            attribution.owner.disposition.value as? FetchDisposition.Committed ?: return null
        if (disposition.attribution !== attribution) return null
        if (!committedEnvelope.matchesWriterAttribution(value, attribution)) return null

        if (currentEnvelope.value != value) return null
        if (currentEnvelope.origin != committedEnvelope.origin) return null
        if (currentEnvelope.meta == null || currentEnvelope.meta === committedEnvelope.meta) {
            return null
        }
        if (currentEnvelope.staleEpochAtCommit < committedEnvelope.staleEpochAtCommit) return null
        if (currentEnvelope.directRevalidationOwner != null) return null

        return ReaderRecord.Row(
            envelope = currentEnvelope,
            readerGen = event.readerGen,
            residenceRevision = residenceRevision,
            successfulWriteSequenceAtObservation =
                event.successfulWriteSequenceAtObservation,
            consumedAttribution =
                consumedAttributionOverride ?: resolution.consumedAttribution,
            activeWriteAttributionAtObservation =
                event.activeWriteAttributionAtObservation,
            rawObservationSequence = event.rawObservationSequence,
        )
    }

    /** Returns the exact already-installed writer envelope, avoiding a duplicate revision bump. */
    private fun recordForExactWriterEnvelopeLocked(
        event: RawReaderEvent.Row<V>,
        attribution: AttributionTag,
        consumedAttribution: AttributionTag?,
    ): ReaderRecord.Row<V>? {
        val value = event.value ?: return null
        val envelope = residence.value ?: return null
        if (!envelope.matchesWriterAttribution(value, attribution)) return null
        return ReaderRecord.Row(
            envelope = envelope,
            readerGen = event.readerGen,
            residenceRevision = residenceRevision,
            successfulWriteSequenceAtObservation =
                event.successfulWriteSequenceAtObservation,
            consumedAttribution = consumedAttribution,
            activeWriteAttributionAtObservation = event.activeWriteAttributionAtObservation,
            rawObservationSequence = event.rawObservationSequence,
        )
    }

    /** Reuses the live same-value envelope for a fenced exact row after residence advancement. */
    private fun recordForCurrentSameValueEnvelopeLocked(
        event: RawReaderEvent.Row<V>,
        consumedAttribution: AttributionTag?,
    ): ReaderRecord.Row<V>? {
        val value = event.value ?: return null
        val envelope = residence.value ?: return null
        if (envelope.value != value) return null
        return ReaderRecord.Row(
            envelope = envelope,
            readerGen = event.readerGen,
            residenceRevision = residenceRevision,
            successfulWriteSequenceAtObservation =
                event.successfulWriteSequenceAtObservation,
            consumedAttribution = consumedAttribution,
            activeWriteAttributionAtObservation = event.activeWriteAttributionAtObservation,
            rawObservationSequence = event.rawObservationSequence,
        )
    }

    private fun ValueEnvelope<V>.matchesWriterAttribution(
        value: Any,
        attribution: AttributionTag,
    ): Boolean =
        this.value == value &&
            origin == attribution.origin &&
            meta === attribution.meta &&
            staleEpochAtCommit == attribution.staleEpochAtCommit &&
            directRevalidationOwner == null

    /** Installs one already-authorized adapter observation while [stateLock] is held. */
    private fun mapReaderRowLocked(
        readerGen: Long,
        event: RawReaderEvent.Row<V>,
        tag: AttributionTag?,
        matchingAttribution: AttributionTag?,
    ): ReaderRecord<V> {
        val value = event.value
        val record = if (value == null) {
            val revision = replaceResidenceLocked(null)
            ReaderRecord.Absent(
                readerGen = readerGen,
                residenceRevision = revision,
                successfulWriteSequenceAtObservation =
                    event.successfulWriteSequenceAtObservation,
                consumedAttribution = tag,
                activeWriteAttributionAtObservation =
                    event.activeWriteAttributionAtObservation,
                rawObservationSequence = event.rawObservationSequence,
            )
        } else {
            val current = residence.value
            val envelope =
                when {
                    matchingAttribution != null ->
                        ValueEnvelope(
                            value = value,
                            origin = matchingAttribution.origin,
                            meta = matchingAttribution.meta,
                            staleEpochAtCommit = matchingAttribution.staleEpochAtCommit,
                        )

                    tag != null ->
                        ValueEnvelope(
                            value = value,
                            origin = Origin.SOT,
                            meta = null,
                            staleEpochAtCommit = mutableState.value.staleEpoch,
                        )

                    current != null && current.value == value -> current

                    else ->
                        ValueEnvelope(
                            value = value,
                            origin = Origin.SOT,
                            meta = null,
                            staleEpochAtCommit = mutableState.value.staleEpoch,
                        )
                }
            val revision = replaceResidenceLocked(envelope)
            ReaderRecord.Row(
                envelope = envelope,
                readerGen = readerGen,
                residenceRevision = revision,
                successfulWriteSequenceAtObservation =
                    event.successfulWriteSequenceAtObservation,
                consumedAttribution = tag,
                activeWriteAttributionAtObservation =
                    event.activeWriteAttributionAtObservation,
                rawObservationSequence = event.rawObservationSequence,
            )
        }
        return record
    }

    /** Converts one adapter outage into a generation-bound record without changing residence. */
    private suspend fun readerFailureRecord(
        readerGen: Long,
        exception: StoreException,
    ): ReaderRecord<V>? =
        stateLock.withLock {
            if (mutableState.value.readerGen != readerGen) return@withLock null
            ReaderRecord.Failure(exception, readerGen, residenceRevision)
        }

    /** Waits out destructive mutation tails, then resolves a queued record against live state. */
    private suspend fun resolveCurrentRecord(record: ReaderRecord<V>): ReaderResolution<V>? {
        while (true) {
            val status = bookkeeper.status(key)
            var barrier: CompletableDeferred<Unit>? = null
            val resolved =
                stateLock.withLock {
                    barrier = destructiveMutationBarrier
                    if (barrier == null) {
                        resolveCurrentRecord(
                            record = record,
                            currentReaderGen = mutableState.value.readerGen,
                            currentResidence = residence.value,
                            currentResidenceRevision = residenceRevision,
                        )?.let { current ->
                            ReaderResolution(
                                record = current,
                                state = mutableState.value,
                                status = status,
                                nowEpochMillis = wallClock.nowEpochMillis(),
                                projectionBase =
                                    when (current) {
                                        is ReaderRecord.Row ->
                                            projectionBaseLocked(
                                                current.envelope,
                                                current.residenceRevision,
                                            )
                                        is ReaderRecord.Absent ->
                                            projectionBaseLocked(
                                                envelope = null,
                                                revision = current.residenceRevision,
                                            )
                                        is ReaderRecord.Failure -> null
                                    },
                            )
                        }
                    } else {
                        null
                    }
                }
            val pending = barrier ?: return resolved
            pending.await()
        }
    }

    /** Installs the fence that prevents reactive delivery from observing a delete mid-tail. */
    private suspend fun beginDestructiveMutation(): CompletableDeferred<Unit> =
        stateLock.withLock {
            check(destructiveMutationBarrier == null) {
                "A destructive source-of-truth mutation is already active."
            }
            CompletableDeferred<Unit>().also { destructiveMutationBarrier = it }
        }

    /** Releases a destructive fence on every terminal path without stranding waiting readers. */
    private suspend fun finishDestructiveMutation(barrier: CompletableDeferred<Unit>) {
        try {
            stateLock.withLock {
                if (destructiveMutationBarrier === barrier) {
                    destructiveMutationBarrier = null
                }
            }
        } finally {
            barrier.complete(Unit)
        }
    }

    /** Plans one read against a coherent snapshot. */
    private fun planFor(
        freshness: Freshness,
        snapshot: KeyState,
        envelope: ValueEnvelope<V>?,
        nowEpochMillis: Long,
        status: KeyStatus?,
    ): FetchPlan =
        validator.plan(
            FreshnessContext(
                hasResidentValue = envelope != null,
                meta = envelope?.meta,
                epochStale = envelope != null && envelope.staleEpochAtCommit < snapshot.staleEpoch,
                freshness = freshness,
                nowEpochMillis = nowEpochMillis,
                status = status,
            ),
        )

    private fun planFor(
        freshness: Freshness,
        snapshot: ResidenceSnapshot<V>,
        envelope: ValueEnvelope<V>? = snapshot.envelope,
    ): FetchPlan =
        planFor(
            freshness = freshness,
            snapshot = snapshot.state,
            envelope = envelope,
            nowEpochMillis = snapshot.nowEpochMillis,
            status = snapshot.status,
        )

    private fun staleServingTolerated(freshness: Freshness): Boolean =
        freshness == Freshness.CachedOrFetch || freshness == Freshness.StaleIfError

    /** Recognizes both fetched envelopes and the exact envelope installed by a writer boundary. */
    private fun isEngineConfirmedEnvelope(envelope: ValueEnvelope<V>): Boolean =
        envelope.origin == Origin.FETCHER || rawCommitResolution?.envelope === envelope

    /** Keeps synthetic-writer SOT provenance instead of re-stamping that exact envelope MEMORY. */
    private fun canRestampEngineMemoryOrigin(
        memoryEnvelope: ValueEnvelope<V>?,
        memoryRevision: Long,
        currentEnvelope: ValueEnvelope<V>?,
        currentRevision: Long,
    ): Boolean =
        canRestampMemoryOrigin(
            memoryEnvelope = memoryEnvelope,
            memoryRevision = memoryRevision,
            currentEnvelope = currentEnvelope,
            currentRevision = currentRevision,
        ) &&
            !(
                memoryEnvelope?.origin == Origin.SOT &&
                    rawCommitResolution?.envelope === memoryEnvelope
            )

    private fun revalidatedSatisfiesDemand(
        freshness: Freshness,
        snapshot: ResidenceSnapshot<V>,
        plan: FetchPlan,
    ): Boolean =
        when (freshness) {
            Freshness.MustBeFresh ->
                snapshot.envelope?.let { envelope ->
                    isEngineConfirmedEnvelope(envelope) &&
                        envelope.meta != null &&
                        envelope.staleEpochAtCommit >= snapshot.state.staleEpoch &&
                        snapshot.status?.durablyStale != true
                } == true

            Freshness.CachedOrFetch,
            Freshness.StaleIfError,
            Freshness.LocalOnly,
            is Freshness.MaxAge,
            -> plan is FetchPlan.Skip
        }

    /** Reserves joined/owned work and returns the exact residence/plan used under stateLock. */
    private suspend fun reserveFetch(
        freshness: Freshness,
        collectorEligibleResidence: ValueEnvelope<V>? = null,
        collectorEligibleRevision: Long? = null,
        enforceCollectorEligibility: Boolean = false,
    ): FetchReservation<V>? {
        while (true) {
            val statusResidenceRevision = stateLock.withLock { residenceRevision }
            val status = bookkeeper.status(key)
            var retryStaleStatus = false
            var pendingRevalidationOwner: FetchTicket? = null
            val planned =
                stateLock.withLock {
                    ensureOpen()
                    val now = wallClock.nowEpochMillis()
                    val snapshot = mutableState.value
                    val currentResidence = residence.value
                    if (residenceRevision != statusResidenceRevision) {
                        retryStaleStatus = true
                        return@withLock null
                    }
                    val directOwner = currentResidence?.directRevalidationOwner
                    val directDisposition =
                        directOwner?.disposition?.value as? FetchDisposition.Revalidated
                    if (
                        directOwner != null &&
                        directDisposition?.envelope === currentResidence &&
                        !directOwner.outcome.isCompleted
                    ) {
                        pendingRevalidationOwner = directOwner
                        return@withLock null
                    }
                    val planningResidence =
                        if (
                            enforceCollectorEligibility &&
                            currentResidence?.directRevalidationOwner != null &&
                            currentResidence !== collectorEligibleResidence
                        ) {
                            collectorEligibleResidence
                        } else {
                            currentResidence
                        }
                    val planningRevision =
                        if (planningResidence === currentResidence) {
                            residenceRevision
                        } else {
                            checkNotNull(collectorEligibleRevision) {
                                "A collector-owned historical residence requires its exact revision."
                            }
                        }
                    val plan = planFor(freshness, snapshot, planningResidence, now, status)
                    if (plan is FetchPlan.Skip) {
                        return@withLock null
                    }
                    val ticket =
                        FetchTicket(
                            outcome = CompletableDeferred(engineJob),
                            requestRevision = residenceRevision,
                            residenceRevisionAtLaunch =
                                currentResidence?.let { residenceRevision },
                            residenceEnvelopeAtLaunch = currentResidence,
                            staleEpochAtLaunch = snapshot.staleEpoch,
                            nowEpochMillisAtLaunch = now,
                            statusAtLaunch = status,
                        )
                    val result = transition(snapshot, KeyEvent.EnsureFetch(ticket))
                    mutableState.value = result.state
                    PlannedFetchEffect(
                        effect = result.effect,
                        collectorEligibleResidence = planningResidence,
                        collectorEligibleRevision = planningRevision,
                        plan = plan,
                    )
                }

            if (retryStaleStatus) continue

            val owner = pendingRevalidationOwner
            if (owner != null) {
                owner.outcome.await()
                continue
            }

            val reservation = planned ?: return null
            val ticket =
                when (val effect = reservation.effect) {
                    is KeyEffect.Launch ->
                        effect.ticket.also { ticket ->
                            launchFetch(
                                ticket,
                                (reservation.plan as? FetchPlan.Conditional)?.etag,
                            )
                        }
                    is KeyEffect.Join -> effect.ticket
                    else -> error("Ensure-fetch transition produced an invalid effect: $effect")
                }
            return FetchReservation(
                ticket = ticket,
                collectorEligibleResidence = reservation.collectorEligibleResidence,
                collectorEligibleRevision = reservation.collectorEligibleRevision,
                plan = reservation.plan,
            )
        }
    }

    /** Returns only the joined/owned identity for non-collector call sites. */
    private suspend fun ensureFetch(freshness: Freshness): FetchTicket? =
        reserveFetch(freshness)?.ticket

    /** Runs the owned fetch independently of any individual waiter. */
    private fun launchFetch(
        ticket: FetchTicket,
        etag: String?,
    ) {
        val fetchJob =
            engineScope.launch(start = CoroutineStart.UNDISPATCHED) {
                var fetchRefHeld = false
                try {
                    val mark = if (telemetry == null) null else TimeSource.Monotonic.markNow()
                    telemetry?.onFetchStarted(key)
                    val outcome =
                        try {
                            residencyHooks.retainFetchRef()
                            fetchRefHeld = true
                            currentCoroutineContext().ensureActive()
                            yield()
                            val result = fetcher.fetch(key, etag)
                            currentCoroutineContext().ensureActive()
                            when (result) {
                                is FetcherResult.Success ->
                                    commitFetch(ticket, result.value, result.etag)

                                is FetcherResult.NotModified ->
                                    commitNotModified(ticket, result.etag)

                                is FetcherResult.Error -> {
                                    if (result.cause is CancellationException) throw result.cause
                                    FetchOutcome.Failed(
                                        exception = fetchResultException(result.cause),
                                        atEpochMillis = wallClock.nowEpochMillis(),
                                    )
                                }

                                FetcherResult.Deleted -> commitDeleted(ticket)
                            }
                        } catch (cancellation: CancellationException) {
                            ticket.outcome.cancel(cancellation)
                            settleFetch(ticket)
                            throw cancellation
                        } catch (failure: Throwable) {
                            FetchOutcome.Failed(
                                exception = fetchException(failure),
                                atEpochMillis = wallClock.nowEpochMillis(),
                            )
                        }

                    finishFetch(ticket, outcome, mark)
                } finally {
                    if (fetchRefHeld) residencyHooks.releaseFetchRef()
                }
            }

        fetchJob.invokeOnCompletion { failure ->
            if (failure != null) ticket.outcome.cancel(storeClosedCancellation())
        }
    }

    /** Persists a value, closes raw source order at normal return, then converges the writer. */
    private suspend fun commitFetch(
        ticket: FetchTicket,
        value: V,
        etag: String?,
    ): FetchOutcome =
        maintenanceCoordinator.withCommit(keyId.namespace) {
            writeLock.withLock {
                val meta = EngineStoreMeta(wallClock.nowEpochMillis(), etag)
                var attribution: AttributionTag? = null
                val effect =
                    stateLock.withLock {
                        val result =
                            transition(
                                mutableState.value,
                                KeyEvent.CommitFetch(
                                    ticket = ticket,
                                    value = value,
                                    meta = meta,
                                ),
                            )
                        attribution = result.state.attribution
                        if (result.effect == KeyEffect.Commit) {
                            val committedAttribution = checkNotNull(attribution)
                            mutableState.value = result.state
                            ticket.disposition.value =
                                FetchDisposition.Committing(
                                    attribution = committedAttribution,
                                    successfulWriteSequenceAtStart =
                                        writeObservationBoundary.value.successfulSequence,
                                )
                            updateWriteObservationBoundary { current ->
                                current.copy(
                                    readerGen = result.state.readerGen,
                                    observedAttribution = committedAttribution,
                                    activeAttribution = committedAttribution,
                                    activeRawPhase = ActiveRawPhase.Unobserved,
                                )
                            }
                        } else {
                            mutableState.value = result.state
                        }
                        result.effect
                    }

                when (effect) {
                    KeyEffect.Superseded -> return@withLock FetchOutcome.Superseded
                    KeyEffect.Commit -> Unit
                    else -> error("Commit-fetch transition produced an invalid effect: $effect")
                }

                val stamped = checkNotNull(attribution)
                try {
                    sot.write(key, value)
                } catch (cancellation: CancellationException) {
                    withContext(NonCancellable) {
                        stateLock.withLock {
                            terminalizeFailedWriteLocked(
                                stamped = stamped,
                                ticket = ticket,
                                disposition = FetchDisposition.Cancelled,
                            )
                        }
                    }
                    throw cancellation
                } catch (failure: Throwable) {
                    val exception = writeException(failure)
                    val atEpochMillis = wallClock.nowEpochMillis()
                    withContext(NonCancellable) {
                        stateLock.withLock {
                            terminalizeFailedWriteLocked(
                                stamped = stamped,
                                ticket = ticket,
                                disposition = FetchDisposition.Failed,
                            )
                        }
                    }
                    bookkeeper.recordFailure(key, atEpochMillis)
                    return@withLock FetchOutcome.Failed(
                        exception = exception,
                        atEpochMillis = atEpochMillis,
                        bookkeepingRecorded = true,
                    )
                }

                // This CAS is the first instruction after normal write return. It separates every
                // mutation-era observation from later source authority without waiting for stateLock.
                val closedWriteBoundary = closeSuccessfulWriteBoundary()
                val committedWriteSequence = withContext(NonCancellable) {
                    val sequence =
                        stateLock.withLock {
                            val committed =
                                convergeSuccessfulWriteLocked(
                                    stamped = stamped,
                                    value = value,
                                    closed = closedWriteBoundary,
                                )
                            ticket.disposition.value =
                                FetchDisposition.Committed(
                                    successfulWriteSequence = committed.successfulWriteSequence,
                                    attribution = stamped,
                                    rawReaderGen = committed.readerGen,
                                    rawCommitCutoff = committed.rawCommitCutoff,
                                    authoritativeRawSequence =
                                        committed.authoritativeRawSequence,
                                )
                            committed.successfulWriteSequence
                        }
                    bookkeeper.recordSuccess(key, meta)
                    sequence
                }
                val disposition =
                    ticket.disposition.value as? FetchDisposition.Committed
                        ?: error("A successful write did not publish Committed disposition.")
                FetchOutcome.Committed(
                    value = value,
                    successfulWriteSequence = committedWriteSequence,
                    attribution = stamped,
                    rawReaderGen = disposition.rawReaderGen,
                    rawCommitCutoff = disposition.rawCommitCutoff,
                    authoritativeRawSequence = disposition.authoritativeRawSequence,
                )
            }
        }

    /**
     * Commits an acknowledged source-of-truth value without fetching or recording success.
     *
     * A synthetic ticket participates only in the attribution/disposition handshake; it never
     * enters the fetch slot and never launches work.
     */
    internal suspend fun applyWrite(value: V) {
        ensureOpen()
        val meta = EngineStoreMeta(wallClock.nowEpochMillis(), etag = null)
        val ticket = FetchTicket(CompletableDeferred())
        maintenanceCoordinator.withCommit(keyId.namespace) {
            writeLock.withLock {
                val stamped =
                    stateLock.withLock {
                        val result =
                            transition(
                                mutableState.value,
                                KeyEvent.ApplyWrite(
                                    ticket = ticket,
                                    value = value,
                                    meta = meta,
                                ),
                            )
                        check(result.effect == KeyEffect.CommitWrite) {
                            "Apply-write transition produced an invalid effect: ${result.effect}"
                        }
                        mutableState.value = result.state
                        val attribution = checkNotNull(result.state.attribution)
                        ticket.disposition.value =
                            FetchDisposition.Committing(
                                attribution = attribution,
                                successfulWriteSequenceAtStart =
                                    writeObservationBoundary.value.successfulSequence,
                            )
                        updateWriteObservationBoundary { current ->
                            current.copy(
                                readerGen = result.state.readerGen,
                                observedAttribution = attribution,
                                activeAttribution = attribution,
                                activeRawPhase = ActiveRawPhase.Unobserved,
                            )
                        }
                        attribution
                    }

                try {
                    sot.write(key, value)
                } catch (cancellation: CancellationException) {
                    withContext(NonCancellable) {
                        stateLock.withLock {
                            terminalizeFailedWriteLocked(
                                stamped = stamped,
                                ticket = ticket,
                                disposition = FetchDisposition.Cancelled,
                            )
                        }
                    }
                    throw cancellation
                } catch (failure: Throwable) {
                    withContext(NonCancellable) {
                        stateLock.withLock {
                            terminalizeFailedWriteLocked(
                                stamped = stamped,
                                ticket = ticket,
                                disposition = FetchDisposition.Failed,
                            )
                        }
                    }
                    throw writeHandleException(failure)
                }

                // This CAS must remain the first non-suspending instruction after normal return.
                val closedWriteBoundary = closeSuccessfulWriteBoundary()
                withContext(NonCancellable) {
                    stateLock.withLock {
                        val committed =
                            convergeSuccessfulWriteLocked(
                                stamped = stamped,
                                value = value,
                                closed = closedWriteBoundary,
                            )
                        ticket.disposition.value =
                            FetchDisposition.Committed(
                                successfulWriteSequence = committed.successfulWriteSequence,
                                attribution = stamped,
                                rawReaderGen = committed.readerGen,
                                rawCommitCutoff = committed.rawCommitCutoff,
                                authoritativeRawSequence =
                                    committed.authoritativeRawSequence,
                            )
                    }
                }
            }
        }
        events.tryEmit(KeyEvents.Written(key, Origin.SOT))
    }

    /** Refreshes resident metadata and durable success without a fetch. */
    internal suspend fun confirmFresh(etag: String?) {
        ensureOpen()
        val meta = EngineStoreMeta(wallClock.nowEpochMillis(), etag)
        maintenanceCoordinator.withCommit(keyId.namespace) {
            writeLock.withLock {
                var replaced = false
                stateLock.withLock state@{
                    val current = residence.value ?: return@state
                    replaceResidenceLocked(
                        ValueEnvelope(
                            value = current.value,
                            origin = current.origin,
                            meta = meta,
                            staleEpochAtCommit = mutableState.value.staleEpoch,
                        ),
                        preserveProjectionAuthorizationLineage = true,
                    )
                    syncObservedAttributionLocked(mutableState.value)
                    replaced = true
                }
                if (replaced) {
                    withContext(NonCancellable) { bookkeeper.recordSuccess(key, meta) }
                }
            }
        }
    }

    /** Closes raw source order and converges its durable winner before bookkeeping. */
    private fun convergeSuccessfulWriteLocked(
        stamped: AttributionTag,
        value: V,
        closed: ClosedWriteBoundary,
    ): DurableWriteResolution {
        val matchingObservation = closed.phase.matchingObservationOrNull()
        val authoritativeRawSequence =
            matchingObservation?.rawSequence

        // RYW makes the successful writer the winner over every pre-close intermediate. Only the
        // exact captured matching token may later reuse this installed FETCHER envelope.
        installWriterEnvelopeLocked(value, stamped)
        val consumed =
            transition(
                mutableState.value,
                KeyEvent.ConsumeAttribution(matchingObservation?.attributionAtObservation),
            )
        mutableState.value = consumed.state
        val consumedAttribution = (consumed.effect as KeyEffect.Consumed).tag
        val revoked = transition(consumed.state, KeyEvent.RevokeAttribution)
        mutableState.value = revoked.state

        syncObservedAttributionLocked(mutableState.value)
        updateWriteObservationBoundary { current ->
            if (current.activeAttribution === stamped) {
                current.copy(
                    activeAttribution = null,
                    activeRawPhase = ActiveRawPhase.Unobserved,
                )
            } else {
                current
            }
        }
        rawCommitResolution =
            RawCommitResolution(
                readerGen = closed.readerGen,
                rawCommitCutoff = closed.rawCommitCutoff,
                authoritativeRawSequence = authoritativeRawSequence,
                residenceRevision = residenceRevision,
                envelope = residence.value,
                consumedAttribution = consumedAttribution,
            )
        return DurableWriteResolution(
            successfulWriteSequence = closed.successfulWriteSequence,
            readerGen = closed.readerGen,
            rawCommitCutoff = closed.rawCommitCutoff,
            authoritativeRawSequence = authoritativeRawSequence,
        )
    }

    private fun installWriterEnvelopeLocked(
        value: V,
        stamped: AttributionTag,
    ) {
        replaceResidenceLocked(
            ValueEnvelope(
                value = value,
                origin = stamped.origin,
                meta = stamped.meta,
                staleEpochAtCommit = stamped.staleEpochAtCommit,
            ),
        )
    }

    /** Atomically closes the raw phase and fences queued pre-return notifications. */
    private fun closeSuccessfulWriteBoundary(): ClosedWriteBoundary {
        while (true) {
            val current = writeObservationBoundary.value
            val nextSequence = current.successfulSequence + 1L
            val pendingWriteAttribution =
                if (
                    current.readerSessionActive &&
                    current.activeRawPhase !is ActiveRawPhase.Matching
                ) {
                    current.activeAttribution ?: current.pendingWriteAttribution
                } else {
                    current.pendingWriteAttribution
                }
            val updated =
                current.copy(
                    successfulSequence = nextSequence,
                    activeRawPhase = ActiveRawPhase.Unobserved,
                    pendingWriteAttribution = pendingWriteAttribution,
                )
            if (writeObservationBoundary.compareAndSet(current, updated)) {
                return ClosedWriteBoundary(
                    readerGen = current.readerGen,
                    rawCommitCutoff = current.latestRawSequence,
                    phase = current.activeRawPhase,
                    successfulWriteSequence = nextSequence,
                )
            }
        }
    }

    /** Atomically revokes failed-write provenance and wakes every captured provisional row. */
    private fun terminalizeFailedWriteLocked(
        stamped: AttributionTag,
        ticket: FetchTicket,
        disposition: FetchDisposition,
    ) {
        require(
            disposition === FetchDisposition.Failed ||
                disposition === FetchDisposition.Cancelled,
        )
        val revoked = transition(mutableState.value, KeyEvent.RevokeAttribution)
        mutableState.value = revoked.state
        syncObservedAttributionLocked(revoked.state)
        updateWriteObservationBoundary { current ->
            if (current.activeAttribution === stamped) {
                current.copy(
                    activeAttribution = null,
                    activeRawPhase = ActiveRawPhase.Unobserved,
                )
            } else {
                current
            }
        }
        ticket.disposition.value = disposition
    }

    /** Applies NotModified only when its launch baseline is still the live residence revision. */
    private suspend fun commitNotModified(
        ticket: FetchTicket,
        etag: String?,
    ): FetchOutcome =
        maintenanceCoordinator.withCommit(keyId.namespace) {
            writeLock.withLock {
                val now = wallClock.nowEpochMillis()
                val baseline = ticket.residenceRevisionAtLaunch
                var refreshedMeta: StoreMeta? = null
                val outcome =
                    stateLock.withLock {
                        val result =
                            transition(mutableState.value, KeyEvent.CommitRevalidated(ticket))
                        val classified =
                            when (result.effect) {
                                KeyEffect.CommitRevalidation -> {
                                    val current = residence.value
                                    if (baseline == null && current == null) {
                                        FetchOutcome.Failed(
                                            exception = notModifiedWithoutValueException(),
                                            atEpochMillis = now,
                                        )
                                    } else if (
                                        baseline == null ||
                                        current == null ||
                                        residenceRevision != baseline
                                    ) {
                                        // A null launch baseline with residence present at commit
                                        // is an obsolete launch snapshot (residence hydrated
                                        // mid-flight), not an adapter-contract violation; only a
                                        // 304 with no value on either side is Failed.
                                        FetchOutcome.ObsoleteRevalidation
                                    } else {
                                        val age = elapsedAge(now, current.meta)
                                        val meta = EngineStoreMeta(now, etag ?: current.meta?.etag)
                                        refreshedMeta = meta
                                        val refreshed =
                                            current.copy(
                                                origin = Origin.FETCHER,
                                                meta = meta,
                                                staleEpochAtCommit = result.state.staleEpoch,
                                                directRevalidationOwner = ticket,
                                            )
                                        val revision = replaceResidenceLocked(refreshed)
                                        FetchOutcome.Revalidated(revision, refreshed, age)
                                    }
                                }

                                KeyEffect.Superseded -> FetchOutcome.Superseded
                                else -> error(
                                    "Commit-revalidated transition produced an invalid effect: " +
                                        result.effect,
                                    )
                            }
                        markDisposition(ticket, classified)
                        mutableState.value = result.state
                        syncObservedAttributionLocked(result.state)
                        classified
                    }
                refreshedMeta?.let { meta ->
                    withContext(NonCancellable) { bookkeeper.recordSuccess(key, meta) }
                }
                outcome
            }
        }

    /** Applies a server deletion only after its ticket is still proven current. */
    private suspend fun commitDeleted(ticket: FetchTicket): FetchOutcome {
        val outcome = commitDeletedUnderFence(ticket)
        if (outcome is FetchOutcome.Deleted) {
            telemetry?.onCleared(key)
            events.tryEmit(KeyEvents.Deleted(key))
        }
        return outcome
    }

    /** Runs the destructive server-deletion transaction without invoking extension code. */
    private suspend fun commitDeletedUnderFence(ticket: FetchTicket): FetchOutcome =
        maintenanceCoordinator.withCommit(keyId.namespace) {
            writeLock.withLock {
                val superseded =
                    stateLock.withLock {
                        val slot = mutableState.value.fetch as? FetchSlot.InFlight
                        slot == null ||
                            slot.ticket !== ticket ||
                            slot.clearEpochAtLaunch != mutableState.value.clearEpoch
                    }
                if (superseded) return@withLock FetchOutcome.Superseded

                withContext(NonCancellable) {
                    val barrier = beginDestructiveMutation()
                    try {
                        val deleteFailure =
                            try {
                                sot.delete(key)
                                null
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: Throwable) {
                                FetchOutcome.Failed(
                                    exception = serverDeletePersistenceException(failure),
                                    atEpochMillis = wallClock.nowEpochMillis(),
                                )
                            }
                        if (deleteFailure != null) return@withContext deleteFailure

                        val absenceRevision = stateLock.withLock {
                            val result =
                                transition(mutableState.value, KeyEvent.CommitDeleted(ticket))
                            check(result.effect == KeyEffect.CommitDelete) {
                                "Commit-deleted transition produced an invalid effect: ${result.effect}"
                            }
                            ticket.disposition.value = FetchDisposition.Deleted
                            mutableState.value = result.state
                            syncObservedAttributionLocked(result.state)
                            replaceResidenceLocked(null)
                        }
                        try {
                            bookkeeper.forget(key)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Throwable) {
                            return@withContext FetchOutcome.Failed(
                                exception =
                                    maintenancePersistenceException(
                                        operation = "server deletion cleanup",
                                        failure = failure,
                                    ),
                                atEpochMillis = wallClock.nowEpochMillis(),
                            )
                        }
                        FetchOutcome.Deleted(absenceRevision)
                    } finally {
                        finishDestructiveMutation(barrier)
                    }
                }
            }
        }

    private suspend fun settleFetch(ticket: FetchTicket) {
        withContext(NonCancellable) {
            stateLock.withLock {
                val result = transition(mutableState.value, KeyEvent.SettleFetch(ticket))
                mutableState.value = result.state
            }
        }
    }

    private suspend fun finishFetch(
        ticket: FetchTicket,
        outcome: FetchOutcome,
        mark: TimeMark?,
    ) {
        if (outcome is FetchOutcome.Failed && !outcome.bookkeepingRecorded) {
            // Failure bookkeeping remains cancellable so engine cancellation releases the ordered
            // write/fence admission. Only the post-release publication tail is non-cancellable.
            val classified = finishFailedFetch(ticket, outcome)
            withContext(NonCancellable) {
                notifyFetchTerminal(classified, mark)
                notifyFetchEvent(classified)
                completeTicket(ticket, classified)
            }
        } else {
            withContext(NonCancellable) {
                val classified =
                    if (outcome is FetchOutcome.Failed) {
                        outcome
                    } else {
                        stateLock.withLock { classifySettledOutcome(ticket, outcome) }
                    }
                notifyFetchTerminal(classified, mark)
                notifyFetchEvent(classified)
                completeTicket(ticket, classified)
            }
        }
    }

    /** Runs the failed-fetch persistence tail and returns only after every engine lock is released. */
    private suspend fun finishFailedFetch(
        ticket: FetchTicket,
        outcome: FetchOutcome.Failed,
    ): FetchOutcome =
        try {
            maintenanceCoordinator.withCommit(keyId.namespace) {
                writeLock.withLock {
                    val classified =
                        stateLock.withLock {
                            classifySettledOutcome(ticket, outcome)
                        }
                    if (classified is FetchOutcome.Failed) {
                        bookkeeper.recordFailure(key, classified.atEpochMillis)
                    }
                    classified
                }
            }
        } catch (cancellation: CancellationException) {
            ticket.outcome.cancel(cancellation)
            settleFetch(ticket)
            throw cancellation
        }

    /** Fires terminal telemetry after settlement and before any waiter observes ticket completion. */
    private fun notifyFetchTerminal(
        outcome: FetchOutcome,
        mark: TimeMark?,
    ) {
        val sink = telemetry ?: return
        when (outcome) {
            is FetchOutcome.Committed,
            is FetchOutcome.Revalidated,
            -> sink.onFetchSucceeded(key, checkNotNull(mark).elapsedNow())

            is FetchOutcome.Failed ->
                sink.onFetchFailed(key, outcome.exception.error, checkNotNull(mark).elapsedNow())

            is FetchOutcome.Deleted,
            FetchOutcome.ObsoleteRevalidation,
            FetchOutcome.Superseded,
            -> Unit
        }
    }

    /** Publishes successful fetch writes after classification and before ticket completion. */
    private fun notifyFetchEvent(outcome: FetchOutcome) {
        if (outcome is FetchOutcome.Committed) {
            events.tryEmit(KeyEvents.Written(key, Origin.FETCHER))
        }
    }

    private fun classifySettledOutcome(
        ticket: FetchTicket,
        outcome: FetchOutcome,
    ): FetchOutcome {
        val result = transition(mutableState.value, KeyEvent.SettleFetch(ticket))
        val classified = when (result.effect) {
            KeyEffect.Superseded -> FetchOutcome.Superseded
            KeyEffect.Settled,
            KeyEffect.Ignored,
            -> outcome

            else -> error("Settle-fetch transition produced an invalid effect: ${result.effect}")
        }
        markDisposition(ticket, classified)
        mutableState.value = result.state
        return classified
    }

    private fun completeTicket(
        ticket: FetchTicket,
        outcome: FetchOutcome,
    ) {
        if (engineJob.isActive) {
            ticket.outcome.complete(outcome)
        } else {
            ticket.outcome.cancel(storeClosedCancellation())
        }
    }

    private fun markDisposition(
        ticket: FetchTicket,
        outcome: FetchOutcome,
    ) {
        ticket.disposition.value =
            when (outcome) {
                is FetchOutcome.Committed ->
                    FetchDisposition.Committed(
                        successfulWriteSequence = outcome.successfulWriteSequence,
                        attribution = outcome.attribution,
                        rawReaderGen = outcome.rawReaderGen,
                        rawCommitCutoff = outcome.rawCommitCutoff,
                        authoritativeRawSequence = outcome.authoritativeRawSequence,
                    )
                is FetchOutcome.Revalidated -> FetchDisposition.Revalidated(outcome.envelope)
                is FetchOutcome.Deleted -> FetchDisposition.Deleted
                is FetchOutcome.Failed -> FetchDisposition.Failed
                FetchOutcome.ObsoleteRevalidation -> FetchDisposition.ObsoleteRevalidation
                FetchOutcome.Superseded -> FetchDisposition.Superseded
            }
    }

    internal suspend fun invalidate() {
        maintenanceCoordinator.withCommit(keyId.namespace) {
            writeLock.withLock {
                try {
                    bookkeeper.markStale(key)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    throw maintenancePersistenceException("invalidate", failure)
                }
                applyEvent(KeyEvent.Invalidate)
            }
        }
        telemetry?.onInvalidated(key)
        events.tryEmit(KeyEvents.Invalidated(key))
    }

    /** Signals resident demand only while the previously advanced watermark still covers it. */
    internal suspend fun invalidateResident() {
        maintenanceCoordinator.withCommit(keyId.namespace) {
            writeLock.withLock {
                if (bookkeeper.status(key)?.durablyStale == true) {
                    applyEvent(KeyEvent.Invalidate)
                }
            }
        }
        telemetry?.onInvalidated(key)
        events.tryEmit(KeyEvents.Invalidated(key))
    }

    /** Deletes persistence first, then performs the irreversible state/bookkeeping tail. */
    internal suspend fun clear() {
        maintenanceCoordinator.withCommit(keyId.namespace) {
            writeLock.withLock {
                withContext(NonCancellable) {
                    val barrier = beginDestructiveMutation()
                    try {
                        try {
                            sot.delete(key)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Throwable) {
                            throw clearPersistenceException(failure)
                        }

                        stateLock.withLock { applyClearTransitionLocked() }
                        try {
                            bookkeeper.forget(key)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Throwable) {
                            throw maintenancePersistenceException("clear", failure)
                        }
                    } finally {
                        finishDestructiveMutation(barrier)
                    }
                }
            }
        }
        telemetry?.onCleared(key)
        events.tryEmit(KeyEvents.Deleted(key))
    }

    /** Applies only the resident clear atom; store-scoped maintenance owns the durable fence. */
    internal suspend fun clearResident() {
        writeLock.withLock {
            withContext(NonCancellable) {
                val barrier = beginDestructiveMutation()
                try {
                    stateLock.withLock { applyClearTransitionLocked() }
                } finally {
                    finishDestructiveMutation(barrier)
                }
            }
        }
    }

    /** Reports one completed store-scoped clear after its maintenance fence has been released. */
    internal fun notifyBulkClearCompleted() {
        telemetry?.onCleared(key)
        events.tryEmit(KeyEvents.Deleted(key))
    }

    /** Applies the clear transition while [stateLock] is held. */
    private fun applyClearTransitionLocked() {
        val result = transition(mutableState.value, KeyEvent.Clear)
        mutableState.value = result.state
        syncObservedAttributionLocked(result.state)
        check(result.effect == KeyEffect.ClearResidence) {
            "Clear transition produced an invalid effect: ${result.effect}"
        }
        replaceResidenceLocked(null)
    }

    /** Direct one-shot hydration used by get and memory-miss stream startup. */
    private suspend fun hydrateFromSot(): ResidenceSnapshot<V> =
        writeLock.withLock {
            val status = bookkeeper.status(key)
            val capturedRevision = stateLock.withLock { residenceRevision }
            val row =
                try {
                    sot.reader(key).first()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    throw readerException(failure)
                }

            stateLock.withLock {
                val current = residence.value
                val resolved =
                    when {
                        current != null -> current
                        residenceRevision != capturedRevision -> current
                        row == null -> residence.value

                        else -> {
                            val currentEpoch = mutableState.value.staleEpoch
                            val staleEpochAtCommit =
                                if (status?.durablyStale == true) currentEpoch - 1L else currentEpoch
                            val hydratedMeta =
                                status?.meta?.let { meta ->
                                    EngineStoreMeta(
                                        writtenAtEpochMillis = meta.writtenAtEpochMillis,
                                        etag = null,
                                    )
                                }
                            ValueEnvelope(
                                value = row,
                                origin = Origin.SOT,
                                meta = hydratedMeta,
                                staleEpochAtCommit = staleEpochAtCommit,
                            ).also(::replaceResidenceLocked)
                        }
                    }
                ResidenceSnapshot(
                    state = mutableState.value,
                    envelope = resolved,
                    revision = residenceRevision,
                    status = status,
                    nowEpochMillis = wallClock.nowEpochMillis(),
                    projectionBase = projectionBaseLocked(resolved, residenceRevision),
                )
            }
        }

    /** Coherent state/residence snapshot used at public delivery boundaries. */
    private suspend fun residenceSnapshot(): ResidenceSnapshot<V> {
        val status = bookkeeper.status(key)
        return stateLock.withLock {
            ResidenceSnapshot(
                state = mutableState.value,
                envelope = residence.value,
                revision = residenceRevision,
                status = status,
                nowEpochMillis = wallClock.nowEpochMillis(),
                projectionBase = projectionBaseLocked(residence.value, residenceRevision),
            )
        }
    }

    /** Distinguishes a newer semantic residence from a same-envelope reader replay. */
    @Suppress("UNCHECKED_CAST")
    private fun residenceAdvancedFrom(
        ticket: FetchTicket,
        snapshot: ResidenceSnapshot<V>,
    ): Boolean {
        val launchEnvelope = ticket.residenceEnvelopeAtLaunch as? ValueEnvelope<V>
        return snapshot.state.staleEpoch > ticket.staleEpochAtLaunch ||
            (
                snapshot.revision != ticket.requestRevision &&
                    snapshot.envelope != launchEnvelope
            )
    }

    /** Builds one live stream with a collector-local serialized delivery controller. */
    internal fun stream(freshness: Freshness): Flow<StoreResult<V>> {
        ensureOpen()
        return channelFlow {
            ensureOpen()
            val producer = this
            val closeHandle =
                closeSignal.invokeOnCompletion { producer.cancel(storeClosedCancellation()) }
            try {
                val memory = residenceSnapshot()
                var startupReaderFailure: StoreException? = null
                var hydrated: ResidenceSnapshot<V>? = null
                if (memory.envelope == null) {
                    try {
                        hydrated = hydrateFromSot()
                    } catch (failure: StoreException) {
                        startupReaderFailure = failure
                    }
                }

                var planning = hydrated ?: residenceSnapshot()
                var planningEpoch = planning.state.staleEpoch
                var planningEligibleEnvelope =
                    if (
                        planning.envelope?.directRevalidationOwner != null &&
                        planning.envelope !== memory.envelope
                    ) {
                        memory.envelope
                    } else {
                        planning.envelope
                    }
                var planningEligibleRevision =
                    if (planningEligibleEnvelope === memory.envelope) {
                        memory.revision
                    } else {
                        planning.revision
                    }
                var plan =
                    planFor(
                        freshness = freshness,
                        snapshot = planning,
                        envelope = planningEligibleEnvelope,
                    )
                val initialReservation =
                    if (plan is FetchPlan.Skip) {
                        null
                    } else {
                        reserveFetch(
                            freshness = freshness,
                            collectorEligibleResidence = planningEligibleEnvelope,
                            collectorEligibleRevision = planningEligibleRevision,
                            enforceCollectorEligibility =
                                planning.envelope?.directRevalidationOwner != null &&
                                    planning.envelope !== planningEligibleEnvelope,
                        )
                    }
                val reservedCollectorEnvelope =
                    initialReservation?.collectorEligibleResidence ?: planningEligibleEnvelope
                val reservedCollectorRevision =
                    initialReservation?.collectorEligibleRevision ?: planningEligibleRevision
                val reservedPlan = initialReservation?.plan ?: plan
                var initialTicket = initialReservation?.ticket
                planning = residenceSnapshot()
                planningEligibleEnvelope =
                    if (
                        planning.envelope?.directRevalidationOwner != null &&
                        planning.envelope !== memory.envelope
                    ) {
                        memory.envelope
                    } else {
                        planning.envelope
                    }
                planningEligibleRevision =
                    if (planningEligibleEnvelope === memory.envelope) {
                        memory.revision
                    } else {
                        planning.revision
                    }
                plan =
                    planFor(
                        freshness = freshness,
                        snapshot = planning,
                        envelope = planningEligibleEnvelope,
                    )

                val delivery =
                    StreamDelivery(
                        producer = producer,
                        freshness = freshness,
                        startupReaderFailure = startupReaderFailure,
                    )
                beforeInitialDeliveryTestGate()
                val initialDelivery = delivery.deliverInitial(
                    memoryEnvelope = memory.envelope,
                    memoryRevision = memory.revision,
                    reservedCollectorEnvelope = reservedCollectorEnvelope,
                    reservedCollectorRevision = reservedCollectorRevision,
                    reservedPlan = reservedPlan,
                    ticket = initialTicket,
                )
                planning = initialDelivery.snapshot
                plan = initialDelivery.plan
                initialTicket = initialDelivery.ticket

                if (freshness == Freshness.MustBeFresh && initialTicket != null) {
                    delivery.startProjectionObserver()
                    while (true) {
                        val ticket = initialTicket ?: break
                        val outcome = ticket.outcome.await()
                        beforeTicketOutcomeDeliveryTestGate()
                        when (outcome) {
                            is FetchOutcome.Committed -> {
                                delivery.retainCommittedTicket(ticket, outcome)
                                break
                            }

                            is FetchOutcome.Revalidated -> {
                                delivery.clearInitialTicket(ticket)
                                when (val delivered = delivery.deliverRevalidated(outcome)) {
                                    RevalidatedDelivery.Delivered -> break
                                    RevalidatedDelivery.Obsolete -> {
                                        initialTicket = ensureFetch(freshness)
                                        if (initialTicket == null) break
                                    }

                                    is RevalidatedDelivery.Replacement ->
                                        initialTicket = delivered.ticket
                                }
                            }

                            FetchOutcome.ObsoleteRevalidation -> {
                                delivery.clearInitialTicket(ticket)
                                initialTicket = ensureFetch(freshness)
                                if (initialTicket == null) break
                            }

                            is FetchOutcome.Failed -> {
                                delivery.clearInitialTicket(ticket)
                                delivery.deliverTerminalOutcome(outcome)
                                close()
                                return@channelFlow
                            }

                            is FetchOutcome.Deleted -> {
                                delivery.clearInitialTicket(ticket)
                                delivery.deliverTerminalOutcome(outcome)
                                close()
                                return@channelFlow
                            }

                            FetchOutcome.Superseded -> {
                                delivery.clearInitialTicket(ticket)
                                delivery.deliverTerminalError(supersededException())
                                close()
                                return@channelFlow
                            }
                        }
                    }
                    planning = residenceSnapshot()
                    planningEpoch =
                        maxOf(
                            planningEpoch,
                            planning.envelope?.staleEpochAtCommit ?: planningEpoch,
                        )
                    plan =
                        planFor(
                            freshness = freshness,
                            snapshot = planning,
                        )
                    initialTicket = null
                }

                delivery.start(
                    planningEpoch = planningEpoch,
                    initialTicket = initialTicket,
                    initialPlan = plan,
                )
                awaitCancellation()
            } finally {
                closeHandle.dispose()
            }
        }.conflateLatestData()
    }

    /** Collector-local sequencer. Every public send occurs while [mutex] is held. */
    private inner class StreamDelivery(
        private val producer: ProducerScope<StoreResult<V>>,
        private val freshness: Freshness,
        private val startupReaderFailure: StoreException?,
    ) {
        private val mutex = Mutex()
        private var publicHasValue = false
        private var loadingVisible = false
        private var localOnlyMissingEmitted = false
        private var watchedTicket: FetchTicket? = null
        private var awaitingCommitted: CommittedReaderWait? = null
        private var handledCommittedTicket: FetchTicket? = null
        private var latestReaderRecord: ReaderRecord<V>? = null
        private var publicServedStale = false
        private var servedStaleForWatchedTicket = false
        private var lastRevalidationRequestedRevision: Long? = null
        private var terminalFailedDemand: FetchTicket? = null
        private var suppressMissingUntilReaderRecovery = startupReaderFailure != null
        private var serverDeletionObserved = false
        private var lastDataFingerprint: DataFingerprint<V>? = null
        private var lastConfirmedRevision: Long? = null
        private var projectionAuthorization: ProjectionAuthorization<V>? = null
        private var projectionObserverStarted = false
        private val pendingFailureHandoffs = ArrayDeque<SettledTicketHandoff>()
        private var ticketLaunchBaseline: TicketLaunchBaselineEntry<V>? = null

        /** Propagates cooperative fetch cancellation to the owning public stream. */
        private suspend fun awaitTicketOutcome(ticket: FetchTicket): FetchOutcome =
            try {
                ticket.outcome.await()
            } catch (cancellation: CancellationException) {
                producer.cancel(cancellation)
                throw cancellation
            }

        /** Plans only from residence this collector is authorized to observe. */
        private fun collectorPlanFor(
            snapshot: ResidenceSnapshot<V>,
            eligibleBaseline: ValueEnvelope<V>? = lastDataFingerprint?.envelope,
            eligibleBaselineRevision: Long? =
                projectionAuthorization?.base?.revision ?: lastConfirmedRevision,
        ): CollectorFetchPlan<V> {
            val current = snapshot.envelope
            val currentIsForeignOwner =
                current?.directRevalidationOwner != null && current !== eligibleBaseline
            val eligibleEnvelope = if (currentIsForeignOwner) eligibleBaseline else current
            val eligibleRevision =
                if (currentIsForeignOwner) {
                    eligibleBaselineRevision ?: snapshot.revision
                } else {
                    snapshot.revision
                }
            val eligibleProjectionBase =
                if (currentIsForeignOwner) {
                    projectionAuthorization?.base?.takeIf { base ->
                        base.envelope === eligibleEnvelope && base.revision == eligibleRevision
                    }
                } else {
                    snapshot.projectionBase
                }
            return CollectorFetchPlan(
                eligibleEnvelope = eligibleEnvelope,
                eligibleRevision = eligibleRevision,
                plan =
                    planFor(
                        freshness = freshness,
                        snapshot = snapshot,
                        envelope = eligibleEnvelope,
                    ),
                currentIsForeignOwner = currentIsForeignOwner,
                eligibleProjectionBase = eligibleProjectionBase,
            )
        }

        private fun collectorPlanFor(
            snapshot: ResidenceSnapshot<V>,
            eligibleBaseline: EligibleBaseline<V>,
        ): CollectorFetchPlan<V> =
            collectorPlanFor(
                snapshot = snapshot,
                eligibleBaseline = eligibleBaseline.envelope,
                eligibleBaselineRevision = eligibleBaseline.revision,
            )

        /** Rechecks collector demand under stateLock without changing the ticket's live baseline. */
        private suspend fun ensureFetchForCollector(
            collectorPlan: CollectorFetchPlan<V>,
        ): FetchTicket? {
            val reservation = reserveFetch(
                freshness = freshness,
                collectorEligibleResidence = collectorPlan.eligibleEnvelope,
                collectorEligibleRevision = collectorPlan.eligibleRevision,
                enforceCollectorEligibility = collectorPlan.currentIsForeignOwner,
            ) ?: return null
            rememberTicketLaunchBaseline(
                reservation.ticket,
                TicketLaunchBaseline(
                    reservation.collectorEligibleResidence,
                    reservation.collectorEligibleRevision,
                    reservation.plan,
                ),
            )
            return reservation.ticket
        }

        private fun rememberTicketLaunchBaseline(
            ticket: FetchTicket,
            baseline: TicketLaunchBaseline<V>,
        ) {
            ticketLaunchBaseline = TicketLaunchBaselineEntry(ticket, baseline)
        }

        /** Keeps a mapped SoT value eligible when a later 304 owns only its refreshed metadata. */
        private fun readerEligibleBaseline(
            notification: ReaderRecord<V>,
            current: ValueEnvelope<V>?,
        ): EligibleBaseline<V> {
            val visible = lastDataFingerprint?.envelope
            val row = notification as? ReaderRecord.Row<V>
            if (current != null && current === visible) {
                return EligibleBaseline(current, row?.residenceRevision ?: checkNotNull(lastConfirmedRevision))
            }
            val mapped = row?.envelope
            val sameValue =
                mapped != null &&
                    if (overlay == null) {
                        mapped.value == current?.value
                    } else {
                        mapped.value === current?.value
                    }
            return if (
                current?.directRevalidationOwner != null &&
                    mapped?.directRevalidationOwner == null &&
                    sameValue
            ) {
                EligibleBaseline(mapped, row.residenceRevision)
            } else {
                EligibleBaseline(
                    envelope = visible,
                    revision = projectionAuthorization?.base?.revision ?: lastConfirmedRevision ?: 0L,
                )
            }
        }

        /** Reconstructs the policy posture that was eligible when [ticket] reserved demand. */
        private fun launchBaselineFor(
            ticket: FetchTicket,
            snapshot: ResidenceSnapshot<V>,
        ): TicketLaunchBaseline<V> {
            val remembered = ticketLaunchBaseline?.takeIf { it.ticket === ticket }?.baseline
            @Suppress("UNCHECKED_CAST")
            val launchBaseline =
                remembered ?: run {
                    val envelope = ticket.residenceEnvelopeAtLaunch as? ValueEnvelope<V>
                    TicketLaunchBaseline(
                        envelope = envelope,
                        revision = ticket.requestRevision,
                        plan =
                            validator.plan(
                                FreshnessContext(
                                    hasResidentValue = envelope != null,
                                    meta = envelope?.meta,
                                    epochStale =
                                        envelope != null &&
                                            envelope.staleEpochAtCommit <
                                            ticket.staleEpochAtLaunch,
                                    freshness = freshness,
                                    nowEpochMillis = ticket.nowEpochMillisAtLaunch,
                                    status = ticket.statusAtLaunch,
                                ),
                            ),
                    )
                }
            val currentPlan =
                planFor(
                    freshness = freshness,
                    snapshot = snapshot,
                    envelope = launchBaseline.envelope,
                )
            return TicketLaunchBaseline(
                envelope = launchBaseline.envelope,
                revision = launchBaseline.revision,
                plan =
                    if (launchBaseline.plan.servesResident) {
                        currentPlan
                    } else {
                        launchBaseline.plan
                    },
            )
        }

        suspend fun deliverInitial(
            memoryEnvelope: ValueEnvelope<V>?,
            memoryRevision: Long,
            reservedCollectorEnvelope: ValueEnvelope<V>?,
            reservedCollectorRevision: Long,
            reservedPlan: FetchPlan,
            ticket: FetchTicket?,
        ): InitialDelivery<V> =
            mutex.withLock {
                if (ticket != null) {
                    rememberTicketLaunchBaseline(
                        ticket,
                        TicketLaunchBaseline(
                            reservedCollectorEnvelope,
                            reservedCollectorRevision,
                            reservedPlan,
                        ),
                    )
                }
                if (
                    overlay != null &&
                    reservedCollectorEnvelope == null &&
                    reservedPlan !is FetchPlan.Skip &&
                    startupReaderFailure == null
                ) {
                    emitLoadingLocked()
                }
                var snapshot = residenceSnapshot()
                var collectorPlan = collectorPlanFor(snapshot, memoryEnvelope, memoryRevision)
                var plan = collectorPlan.plan
                var effectiveTicket = ticket
                if (plan !is FetchPlan.Skip && effectiveTicket == null) {
                    effectiveTicket = ensureFetchForCollector(collectorPlan)
                    snapshot = residenceSnapshot()
                    collectorPlan = collectorPlanFor(snapshot, memoryEnvelope, memoryRevision)
                    plan = collectorPlan.plan
                }
                afterInitialPlanningSnapshotTestGate()
                val pendingSettledTailAtRecapture =
                    effectiveTicket != null &&
                        !effectiveTicket.outcome.isCompleted &&
                        (snapshot.state.fetch as? FetchSlot.InFlight)?.ticket !== effectiveTicket &&
                        effectiveTicket.requestRevision != snapshot.revision
                val pendingExactRevalidation =
                    pendingSettledTailAtRecapture &&
                        (effectiveTicket.disposition.value as? FetchDisposition.Revalidated)
                            ?.envelope === snapshot.envelope
                if (pendingExactRevalidation) {
                    if (
                        reservedCollectorEnvelope != null &&
                        reservedPlan.servesResident &&
                        plan.servesResident
                    ) {
                        deliverDataLocked(
                            reservedCollectorEnvelope,
                            revision = reservedCollectorRevision,
                            authority = DataDeliveryAuthority.CollectorBaseline,
                        )
                    } else {
                        emitLoadingLocked()
                    }
                } else if (
                    pendingSettledTailAtRecapture &&
                    (snapshot.envelope == null || !plan.servesResident)
                ) {
                    emitLoadingLocked()
                }
                val observedOuterOutcome =
                    when {
                        effectiveTicket == null -> null
                        effectiveTicket.outcome.isCompleted -> awaitTicketOutcome(effectiveTicket)
                        pendingSettledTailAtRecapture -> awaitTicketOutcome(effectiveTicket)
                        else -> null
                    }
                if (pendingSettledTailAtRecapture) {
                    snapshot = residenceSnapshot()
                    collectorPlan = collectorPlanFor(snapshot, memoryEnvelope, memoryRevision)
                    plan = collectorPlan.plan
                }
                val completedOuterOutcome =
                    observedOuterOutcome?.takeIf { outcome ->
                        freshness != Freshness.MustBeFresh &&
                            effectiveTicket?.let { ticket ->
                                if (outcome is FetchOutcome.Failed) {
                                    residenceAdvancedFrom(ticket, snapshot)
                                } else {
                                    ticket.requestRevision != snapshot.revision
                                }
                            } == true
                    }
                val completedExactRevalidation =
                    (completedOuterOutcome as? FetchOutcome.Revalidated)
                        ?.takeIf { snapshot.envelope === it.envelope }
                var completedExactRevalidationDelivery: RevalidatedDelivery? = null
                if (
                    completedExactRevalidation != null &&
                    !revalidatedSatisfiesDemand(
                        snapshot,
                        planFor(
                            freshness = freshness,
                            snapshot = snapshot,
                        ),
                    )
                ) {
                    val revalidationDelivery =
                        deliverRevalidatedLocked(
                            outcome = completedExactRevalidation,
                            watchReplacement = false,
                        )
                    completedExactRevalidationDelivery = revalidationDelivery
                    effectiveTicket =
                        when (val delivery = revalidationDelivery) {
                            is RevalidatedDelivery.Replacement -> delivery.ticket
                            RevalidatedDelivery.Delivered -> null
                            RevalidatedDelivery.Obsolete -> {
                                snapshot = residenceSnapshot()
                                collectorPlan =
                                    collectorPlanFor(snapshot, memoryEnvelope, memoryRevision)
                                plan = collectorPlan.plan
                                if (plan is FetchPlan.Skip) {
                                    null
                                } else {
                                    ensureFetchForCollector(collectorPlan)
                                }
                            }
                        }
                    snapshot = residenceSnapshot()
                    collectorPlan = collectorPlanFor(snapshot, memoryEnvelope, memoryRevision)
                    plan = collectorPlan.plan
                }
                if (completedOuterOutcome is FetchOutcome.Committed) {
                    installCommittedWaitLocked(
                        checkNotNull(effectiveTicket),
                        completedOuterOutcome,
                    )
                } else if (
                    completedOuterOutcome != null &&
                    completedExactRevalidation == null &&
                    (completedOuterOutcome !is FetchOutcome.Deleted || snapshot.envelope != null)
                ) {
                    // The outer ticket no longer covers the final residence. Hand ownership to
                    // its replacement before the first public send, then surface the old outcome
                    // with its pre-handoff served-stale state.
                    val outerTicket = checkNotNull(effectiveTicket)
                    if (completedOuterOutcome is FetchOutcome.Deleted) {
                        handleOutcomeLocked(outerTicket, completedOuterOutcome, false)
                        serverDeletionObserved = false
                        effectiveTicket = null
                    }
                    val replacement =
                        if (plan is FetchPlan.Skip) null else ensureFetchForCollector(collectorPlan)
                    if (replacement != null) {
                        if (completedOuterOutcome is FetchOutcome.Failed) {
                            enqueueFailureHandoff(
                                SettledTicketHandoff(
                                    ticket = outerTicket,
                                    outcome = completedOuterOutcome,
                                    servedStale = false,
                                ),
                            )
                        }
                        effectiveTicket = replacement
                    }
                    snapshot = residenceSnapshot()
                    collectorPlan = collectorPlanFor(snapshot, memoryEnvelope, memoryRevision)
                    plan = collectorPlan.plan
                }
                var initialPublicDeliveryCompleted =
                    completedExactRevalidationDelivery == RevalidatedDelivery.Delivered ||
                        (completedExactRevalidationDelivery is RevalidatedDelivery.Replacement &&
                            completedExactRevalidationDelivery.publicDeliveryCompleted)
                if (
                    effectiveTicket != null &&
                    (effectiveTicket !== ticket || observedOuterOutcome == null)
                ) {
                    beforeReplacementDispositionClassificationTestGate()
                }
                val classifiedReplacementTickets = mutableSetOf<FetchTicket>()
                var replacementClassifications = 0
                var replacementClassificationCapped = false
                var replacementCommitRetainedServableRow = false
                var replacementCommittingTailRetained = false
                replacementClassification@ while (!initialPublicDeliveryCompleted) {
                    val candidate = effectiveTicket ?: break
                    if (candidate === ticket && observedOuterOutcome != null) break
                    snapshot = residenceSnapshot()
                    collectorPlan = collectorPlanFor(snapshot, memoryEnvelope, memoryRevision)
                    plan = collectorPlan.plan
                    val disposition = candidate.disposition.value
                    if (disposition is FetchDisposition.InFlight) break
                    val originalServableBaseline =
                        candidate === ticket &&
                            reservedCollectorEnvelope != null &&
                            reservedPlan.servesResident &&
                            collectorPlan.plan.servesResident &&
                            collectorPlan.eligibleEnvelope == reservedCollectorEnvelope
                    if (disposition is FetchDisposition.Committing) {
                        if (originalServableBaseline) {
                            deliverDataLocked(
                                envelope = checkNotNull(reservedCollectorEnvelope),
                                revision = reservedCollectorRevision,
                                originOverride =
                                    if (
                                        canRestampEngineMemoryOrigin(
                                            memoryEnvelope = memoryEnvelope,
                                            memoryRevision = memoryRevision,
                                            currentEnvelope = snapshot.envelope,
                                            currentRevision = snapshot.revision,
                                        )
                                    ) {
                                        Origin.MEMORY
                                    } else {
                                        null
                                    },
                                authority = DataDeliveryAuthority.CollectorBaseline,
                            )
                        } else if (
                            collectorPlan.eligibleEnvelope == null ||
                            !collectorPlan.plan.servesResident ||
                            collectorPlan.eligibleEnvelope.value == disposition.attribution.value
                        ) {
                            emitLoadingLocked()
                        }
                        replacementCommittingTailRetained = true
                        break@replacementClassification
                    }
                    if (++replacementClassifications > 32) {
                        if (
                            collectorPlan.eligibleEnvelope == null ||
                            !collectorPlan.plan.servesResident
                        ) {
                            emitLoadingLocked()
                        }
                        replacementClassificationCapped = true
                        break@replacementClassification
                    }
                    if (!classifiedReplacementTickets.add(candidate)) {
                        break@replacementClassification
                    }
                    var revalidationReplacementReservedBeforeTail: FetchTicket? = null
                    when (disposition) {
                        is FetchDisposition.Committed -> {
                            if (
                                candidate === ticket ||
                                collectorPlan.eligibleEnvelope == null ||
                                !collectorPlan.plan.servesResident ||
                                collectorPlan.eligibleEnvelope.value ==
                                disposition.attribution.value
                            ) {
                                emitLoadingLocked()
                            } else {
                                replacementCommitRetainedServableRow = true
                            }
                        }

                        is FetchDisposition.Revalidated -> {
                            if (snapshot.envelope === disposition.envelope) {
                                val baseline = launchBaselineFor(candidate, snapshot)
                                val currentPlan =
                                    planFor(
                                        freshness = freshness,
                                        snapshot = snapshot,
                                    )
                                val currentSatisfiesDemand =
                                    revalidatedSatisfiesDemand(snapshot, currentPlan)
                                // Revalidated publishes before its bookkeeping/outcome tail. The
                                // old durable status cannot supersede an owner covering this epoch.
                                val exactOwnerCoversCurrentEpoch =
                                    disposition.envelope.staleEpochAtCommit >=
                                        snapshot.state.staleEpoch
                                val replacement =
                                    if (currentSatisfiesDemand || exactOwnerCoversCurrentEpoch) {
                                        null
                                    } else {
                                        ensureFetchForCollector(
                                            CollectorFetchPlan(
                                                eligibleEnvelope = baseline.envelope,
                                                eligibleRevision = baseline.revision,
                                                plan = baseline.plan,
                                                currentIsForeignOwner =
                                                    snapshot.envelope
                                                        ?.directRevalidationOwner != null &&
                                                        snapshot.envelope !== baseline.envelope,
                                            ),
                                        )
                                    }
                                revalidationReplacementReservedBeforeTail = replacement
                                if (
                                    currentSatisfiesDemand ||
                                    exactOwnerCoversCurrentEpoch ||
                                    replacement != null
                                ) {
                                    if (
                                        baseline.envelope != null &&
                                        baseline.plan.servesResident
                                    ) {
                                        deliverDataLocked(
                                            baseline.envelope,
                                            revision = baseline.revision,
                                            authority = DataDeliveryAuthority.CollectorBaseline,
                                        )
                                    } else {
                                        emitLoadingLocked()
                                    }
                                }
                            } else if (
                                collectorPlan.eligibleEnvelope == null ||
                                !collectorPlan.plan.servesResident
                            ) {
                                emitLoadingLocked()
                            }
                        }

                        else -> {
                            if (
                                collectorPlan.eligibleEnvelope == null ||
                                !collectorPlan.plan.servesResident
                            ) {
                                emitLoadingLocked()
                            }
                        }
                    }

                    val outcome = candidate.outcome.await()
                    snapshot = residenceSnapshot()
                    collectorPlan = collectorPlanFor(snapshot, memoryEnvelope, memoryRevision)
                    plan = collectorPlan.plan
                    when (outcome) {
                        is FetchOutcome.Committed -> {
                            installCommittedWaitLocked(candidate, outcome)
                            break@replacementClassification
                        }

                        is FetchOutcome.Revalidated -> {
                            if (revalidationReplacementReservedBeforeTail != null) {
                                effectiveTicket = revalidationReplacementReservedBeforeTail
                                initialPublicDeliveryCompleted = true
                                continue@replacementClassification
                            }
                            if (snapshot.envelope !== outcome.envelope) {
                                effectiveTicket =
                                    if (plan is FetchPlan.Skip) {
                                        null
                                    } else {
                                        ensureFetchForCollector(collectorPlan)
                                    }
                                continue@replacementClassification
                            }
                            when (
                                val delivery =
                                    deliverRevalidatedLocked(
                                        outcome = outcome,
                                        watchReplacement = false,
                                    )
                            ) {
                                RevalidatedDelivery.Delivered -> {
                                    effectiveTicket = null
                                    initialPublicDeliveryCompleted = true
                                }

                                RevalidatedDelivery.Obsolete -> {
                                    snapshot = residenceSnapshot()
                                    collectorPlan =
                                        collectorPlanFor(snapshot, memoryEnvelope, memoryRevision)
                                    plan = collectorPlan.plan
                                    effectiveTicket =
                                        if (plan is FetchPlan.Skip) {
                                            null
                                        } else {
                                            ensureFetchForCollector(collectorPlan)
                                        }
                                }

                                is RevalidatedDelivery.Replacement -> {
                                    effectiveTicket = delivery.ticket
                                    initialPublicDeliveryCompleted =
                                        delivery.publicDeliveryCompleted
                                }
                            }
                        }

                        is FetchOutcome.Failed -> {
                            if (residenceAdvancedFrom(candidate, snapshot)) {
                                enqueueFailureHandoff(
                                    SettledTicketHandoff(
                                        ticket = candidate,
                                        outcome = outcome,
                                        servedStale = publicServedStale,
                                    ),
                                )
                                effectiveTicket =
                                    if (plan is FetchPlan.Skip) {
                                        null
                                    } else {
                                        ensureFetchForCollector(collectorPlan)
                                    }
                                continue@replacementClassification
                            }
                            break@replacementClassification
                        }

                        is FetchOutcome.Deleted -> {
                            handleOutcomeLocked(candidate, outcome, false)
                            effectiveTicket = null
                            initialPublicDeliveryCompleted = true
                        }

                        FetchOutcome.ObsoleteRevalidation,
                        FetchOutcome.Superseded,
                        -> {
                            effectiveTicket =
                                if (plan is FetchPlan.Skip) {
                                    null
                                } else {
                                    ensureFetchForCollector(collectorPlan)
                                }
                        }
                    }
                }
                snapshot = residenceSnapshot()
                collectorPlan = collectorPlanFor(snapshot, memoryEnvelope, memoryRevision)
                plan = collectorPlan.plan
                // A commit can land after the earlier outcome snapshot. Recognize only this
                // ticket's exact writer envelope before generic residence delivery so an absent
                // start keeps its Loading -> causally observed Data contract.
                val finalCommittedDisposition =
                    effectiveTicket?.disposition?.value as? FetchDisposition.Committed
                if (
                    awaitingCommitted == null &&
                    finalCommittedDisposition != null &&
                    snapshot.envelope?.matchesWriterAttribution(
                        finalCommittedDisposition.attribution.value,
                        finalCommittedDisposition.attribution,
                    ) == true
                ) {
                    installCommittedWaitLocked(
                        checkNotNull(effectiveTicket),
                        finalCommittedDisposition,
                    )
                }
                val currentResidencePlan =
                    planFor(
                        freshness = freshness,
                        snapshot = snapshot,
                    )
                val reservedBaselineCurrentPlan =
                    planFor(
                        freshness = freshness,
                        snapshot = snapshot,
                        envelope = reservedCollectorEnvelope,
                    )
                val memoryOverride =
                    canRestampEngineMemoryOrigin(
                        memoryEnvelope = memoryEnvelope,
                        memoryRevision = memoryRevision,
                        currentEnvelope = snapshot.envelope,
                        currentRevision = snapshot.revision,
                    )
                if (effectiveTicket != null) {
                    if (watchedTicket !== effectiveTicket) {
                        watchedTicket = effectiveTicket
                        servedStaleForWatchedTicket = publicServedStale
                    }
                    lastRevalidationRequestedRevision = effectiveTicket.requestRevision
                } else if (awaitingCommitted == null) {
                    watchedTicket = null
                }
                when {
                    initialPublicDeliveryCompleted -> Unit

                    replacementCommittingTailRetained -> Unit

                    // The loop already emitted Loading when the final residence was absent or
                    // withheld. A policy-servable different row stays silent here until the
                    // retained effective-ticket watcher classifies its ordered tail.
                    replacementClassificationCapped -> Unit

                    pendingSettledTailAtRecapture &&
                        awaitingCommitted != null &&
                        snapshot.envelope != null &&
                        plan !is FetchPlan.Skip &&
                        plan.servesResident -> Unit

                    completedExactRevalidation != null &&
                        completedExactRevalidationDelivery == null &&
                        reservedPlan.servesResident &&
                        reservedBaselineCurrentPlan.servesResident &&
                        reservedCollectorEnvelope != null &&
                        ticket?.residenceRevisionAtLaunch == ticket?.requestRevision &&
                        revalidatedSatisfiesDemand(snapshot, currentResidencePlan) ->
                        deliverDataLocked(
                            envelope = reservedCollectorEnvelope,
                            revision = reservedCollectorRevision,
                            originOverride =
                                if (
                                    canRestampEngineMemoryOrigin(
                                        memoryEnvelope = memoryEnvelope,
                                        memoryRevision = memoryRevision,
                                        currentEnvelope = reservedCollectorEnvelope,
                                        currentRevision = reservedCollectorRevision,
                                    )
                                ) {
                                    Origin.MEMORY
                                } else {
                                    null
                                },
                            authority = DataDeliveryAuthority.CollectorBaseline,
                        )

                    replacementCommitRetainedServableRow && awaitingCommitted != null -> Unit

                    awaitingCommitted != null ||
                        (completedExactRevalidation != null &&
                            completedExactRevalidationDelivery == null) ->
                        emitLoadingLocked()

                    collectorPlan.currentIsForeignOwner &&
                        collectorPlan.eligibleEnvelope != null &&
                        plan.servesResident ->
                        deliverDataLocked(
                            envelope = collectorPlan.eligibleEnvelope,
                            revision = collectorPlan.eligibleRevision,
                            projectionBase = collectorPlan.eligibleProjectionBase,
                            originOverride =
                                if (collectorPlan.eligibleEnvelope === memoryEnvelope) {
                                    Origin.MEMORY
                                } else {
                                    null
                                },
                            authority = DataDeliveryAuthority.CollectorBaseline,
                        )

                    collectorPlan.currentIsForeignOwner -> emitLoadingLocked()

                    freshness == Freshness.LocalOnly && collectorPlan.eligibleEnvelope == null -> {
                        if (startupReaderFailure == null) {
                            if (snapshot.envelope == null) {
                                deliverAbsenceLocked(snapshot.revision) {
                                    emitLocalOnlyMissingLocked()
                                }
                            } else {
                                emitLocalOnlyMissingLocked()
                            }
                        }
                    }

                    collectorPlan.eligibleEnvelope != null && plan.servesResident ->
                        deliverDataLocked(
                            envelope = collectorPlan.eligibleEnvelope,
                            revision = collectorPlan.eligibleRevision,
                            projectionBase = collectorPlan.eligibleProjectionBase,
                            originOverride = if (memoryOverride) Origin.MEMORY else null,
                            authority =
                                if (
                                    memoryOverride ||
                                    collectorPlan.eligibleEnvelope.directRevalidationOwner != null
                                ) {
                                    DataDeliveryAuthority.CollectorBaseline
                                } else {
                                    DataDeliveryAuthority.Generic
                                },
                        )

                    else -> {
                        if (snapshot.envelope == null && startupReaderFailure == null) {
                            deliverAbsenceLocked(snapshot.revision) { emitLoadingLocked() }
                        } else {
                            emitLoadingLocked()
                        }
                    }
                }
                startupReaderFailure?.let { emitErrorLocked(it) }
                if (
                    !replacementClassificationCapped &&
                    !replacementCommittingTailRetained &&
                    awaitingCommitted == null &&
                    effectiveTicket?.disposition?.value !is FetchDisposition.Revalidated &&
                    effectiveTicket?.disposition?.value !is FetchDisposition.Committing &&
                    effectiveTicket?.disposition?.value !is FetchDisposition.Committed
                ) {
                    flushPendingFailureHandoffsLocked()
                }
                InitialDelivery(snapshot, plan, effectiveTicket)
            }

        suspend fun deliverRevalidated(
            outcome: FetchOutcome.Revalidated,
        ): RevalidatedDelivery =
            mutex.withLock {
                deliverRevalidatedLocked(outcome, watchReplacement = false)
            }

        /** Rechecks policy after a 304 because epochs and wall-clock age may advance meanwhile. */
        private suspend fun deliverRevalidatedLocked(
            outcome: FetchOutcome.Revalidated,
            watchReplacement: Boolean,
        ): RevalidatedDelivery {
            var snapshot = residenceSnapshot()
            if (snapshot.envelope !== outcome.envelope) return RevalidatedDelivery.Obsolete
            var plan =
                planFor(
                    freshness = freshness,
                    snapshot = snapshot,
                )
            var demandSatisfied = revalidatedSatisfiesDemand(snapshot, plan)

            var replacement: FetchTicket? = null
            if (!demandSatisfied) {
                replacement =
                    ensureFetchForCollector(
                        CollectorFetchPlan(
                            eligibleEnvelope = snapshot.envelope,
                            eligibleRevision = snapshot.revision,
                            plan = plan,
                            currentIsForeignOwner = false,
                        ),
                    )
                if (replacement != null) {
                    if (watchReplacement) {
                        watchTicketLocked(replacement)
                    } else {
                        watchedTicket = replacement
                        servedStaleForWatchedTicket = publicServedStale
                        lastRevalidationRequestedRevision = replacement.requestRevision
                    }
                }
                snapshot = residenceSnapshot()
                if (snapshot.envelope !== outcome.envelope) {
                    return replacement?.let {
                        RevalidatedDelivery.Replacement(
                            ticket = it,
                            publicDeliveryCompleted = false,
                        )
                    }
                        ?: RevalidatedDelivery.Obsolete
                }
                plan =
                    planFor(
                        freshness = freshness,
                        snapshot = snapshot,
                    )
                demandSatisfied = revalidatedSatisfiesDemand(snapshot, plan)
            }

            if (
                replacement != null &&
                replacement.disposition.value !is FetchDisposition.InFlight
            ) {
                return RevalidatedDelivery.Replacement(
                    ticket = replacement,
                    publicDeliveryCompleted = false,
                )
            }

            serverDeletionObserved = false
            val envelope = checkNotNull(snapshot.envelope)
            when {
                demandSatisfied ->
                    if (
                        !emitRevalidatedLocked(
                            outcome = outcome,
                            envelope = envelope,
                            projectionBase =
                                snapshot.projectionBase?.takeIf { captured ->
                                    captured.envelope === outcome.envelope &&
                                        captured.revision == outcome.residenceRevision
                                },
                        )
                    ) {
                        return RevalidatedDelivery.Obsolete
                    }

                plan.servesResident ->
                    deliverDataLocked(
                        envelope,
                        revision = outcome.residenceRevision,
                        projectionBase =
                            snapshot.projectionBase?.takeIf { captured ->
                                captured.envelope === outcome.envelope &&
                                    captured.revision == outcome.residenceRevision
                            },
                        authority = DataDeliveryAuthority.OwnerOutcome,
                    )
                else -> emitLoadingLocked()
            }
            return replacement?.let {
                RevalidatedDelivery.Replacement(
                    ticket = it,
                    publicDeliveryCompleted = true,
                )
            }
                ?: RevalidatedDelivery.Delivered
        }

        /** Publishes the exact owner's 304 as a lifecycle signal and adopts its fresh envelope. */
        private suspend fun emitRevalidatedLocked(
            outcome: FetchOutcome.Revalidated,
            envelope: ValueEnvelope<V>,
            projectionBase: ProjectionBase<V>?,
        ): Boolean {
            if (overlay == null) {
                producer.send(StoreResult.Revalidated(outcome.age))
                telemetry?.onServe(key, envelope.origin)
                lastDataFingerprint = DataFingerprint(envelope)
                lastConfirmedRevision = outcome.residenceRevision
                publicHasValue = true
                loadingVisible = false
                localOnlyMissingEmitted = false
                publicServedStale = false
                servedStaleForWatchedTicket = false
                terminalFailedDemand = null
                lastRevalidationRequestedRevision = outcome.residenceRevision
                return true
            }

            beforeProjectionAuthorizationTestGate()
            val authorizationBase =
                projectionBase?.also { captured ->
                    check(
                        captured.envelope === envelope &&
                            captured.revision == outcome.residenceRevision,
                    ) {
                        "A captured projection base must name the revalidated residence."
                    }
                } ?: ProjectionBase(envelope, outcome.residenceRevision)
            val authorization =
                projectionAuthorization(
                    base = authorizationBase,
                    originOverride = null,
                    authority = DataDeliveryAuthority.OwnerOutcome,
                )
            val previousAuthorization = projectionAuthorization
            projectionAuthorization = authorization
            val readiness = awaitProjectionReadiness(authorization)
            if (readiness is ProjectionReadiness.Obsolete) {
                revokeObsoleteProjectionAuthorization(authorization, previousAuthorization)
                return false
            }
            val projection = (readiness as ProjectionReadiness.Ready).projection
            val revalidatedOrigin =
                when (projection) {
                    is Projection.Value -> {
                        val previousValue =
                            lastDataFingerprint?.let { fingerprint ->
                                if (fingerprint.isOverlaid) {
                                    fingerprint.overlaidValue
                                } else {
                                    fingerprint.envelope?.value
                                }
                            }
                        if (!publicHasValue || previousValue != envelope.value) {
                            deliverConfirmedDataLocked(
                                envelope = envelope,
                                revision = outcome.residenceRevision,
                                authority = DataDeliveryAuthority.OwnerOutcome,
                            )
                        } else {
                            lastDataFingerprint = DataFingerprint(envelope)
                            lastConfirmedRevision = outcome.residenceRevision
                            publicHasValue = true
                            loadingVisible = false
                            localOnlyMissingEmitted = false
                            publicServedStale = false
                            servedStaleForWatchedTicket = false
                        }
                        envelope.origin
                    }

                    is Projection.Overlaid -> {
                        renderOverlaidLocked(authorization, projection.value)
                        Origin.OVERLAY
                    }

                    Projection.Absent -> {
                        emitLoadingLocked(preserveProjectionAuthorization = true)
                        null
                    }
                }
            revalidatedOrigin?.let { telemetry?.onServe(key, it) }
            terminalFailedDemand = null
            lastRevalidationRequestedRevision = outcome.residenceRevision
            producer.send(StoreResult.Revalidated(outcome.age))
            return true
        }

        private fun revalidatedSatisfiesDemand(
            snapshot: ResidenceSnapshot<V>,
            plan: FetchPlan,
        ): Boolean = this@KeyEngine.revalidatedSatisfiesDemand(freshness, snapshot, plan)

        private fun ReaderRecord.Row<V>.snapshot(
            resolution: ReaderResolution<V>,
        ): ResidenceSnapshot<V> =
            ResidenceSnapshot(
                state = resolution.state,
                envelope = envelope,
                revision = residenceRevision,
                status = resolution.status,
                nowEpochMillis = resolution.nowEpochMillis,
                projectionBase = resolution.projectionBase,
            )

        /** Keeps an equal late reader replay inside the demand already settled by a failure. */
        private fun failedDemandStillCovers(snapshot: ResidenceSnapshot<V>): Boolean {
            val failedTicket = terminalFailedDemand ?: return false
            if (residenceAdvancedFrom(failedTicket, snapshot)) {
                terminalFailedDemand = null
                return false
            }
            lastRevalidationRequestedRevision = snapshot.revision
            return true
        }

        suspend fun clearInitialTicket(ticket: FetchTicket) {
            mutex.withLock {
                if (watchedTicket === ticket) watchedTicket = null
                if (awaitingCommitted?.ticket === ticket) awaitingCommitted = null
                if (handledCommittedTicket === ticket) handledCommittedTicket = null
            }
        }

        suspend fun retainCommittedTicket(
            ticket: FetchTicket,
            outcome: FetchOutcome.Committed,
        ) {
            mutex.withLock { retainCommittedTicketLocked(ticket, outcome) }
        }

        suspend fun deliverTerminalError(exception: StoreException) {
            mutex.withLock { emitErrorLocked(exception, servedStaleOverride = false) }
        }

        suspend fun deliverTerminalOutcome(outcome: FetchOutcome) {
            mutex.withLock { surfaceTerminalOutcomeLocked(outcome, servedStaleForTicket = false) }
        }

        /** Starts the configured projection observer once, including before a MustBeFresh wait. */
        fun startProjectionObserver() {
            val snapshots = projectionSnapshot ?: return
            if (projectionObserverStarted) return
            projectionObserverStarted = true
            producer.launch {
                snapshots.collect { snapshot ->
                    if (
                        snapshot is ProjectionSnapshot.Ready ||
                        snapshot is ProjectionSnapshot.Terminal
                    ) {
                        beforeProjectionDeliveryLockTestGate()
                        mutex.withLock {
                            beforeProjectionDeliveryTestGate()
                            deliverProjectionSnapshotLocked(snapshot)
                            afterProjectionDeliveryTestGate()
                        }
                    }
                }
            }
        }

        fun start(
            planningEpoch: Long,
            initialTicket: FetchTicket?,
            initialPlan: FetchPlan,
        ) {
            if (initialTicket != null) {
                watchedTicket = initialTicket
                if (initialPlan !is FetchPlan.Skip) {
                    lastRevalidationRequestedRevision = initialTicket.requestRevision
                }
                observeCommittedDisposition(initialTicket)
            }

            producer.launch {
                readerRecords.collect { record -> deliverRecord(record) }
            }
            startProjectionObserver()
            producer.launch {
                initialTicket?.let { ticket ->
                    val outcome = awaitTicketOutcome(ticket)
                    beforeTicketOutcomeDeliveryTestGate()
                    mutex.withLock {
                        deliverWatchedOutcomeLocked(ticket, outcome)
                    }
                }
                state.staleEpochsAfter(planningEpoch).collect {
                    mutex.withLock {
                        serverDeletionObserved = false
                        requestAndDeliverLocked(
                            forceRequest = true,
                            suppressResidentIfVisible = true,
                        )
                    }
                }
            }
        }

        private suspend fun deliverRecord(record: ReaderRecord<V>) {
            beforeReaderDeliveryLockTestGate(record)
            mutex.withLock {
                beforeReaderDeliveryTestGate()
                if (record !is ReaderRecord.Failure) latestReaderRecord = record
                deliverReaderRecordLocked(record)
            }
        }

        /** Resolves and delivers one pipeline notification without leaving the delivery mutex. */
        private suspend fun deliverReaderRecordLocked(record: ReaderRecord<V>) {
            val resolution = resolveCurrentRecord(record) ?: return
            when (val resolved = resolution.record) {
                is ReaderRecord.Failure -> emitErrorLocked(resolved.exception)
                is ReaderRecord.Row ->
                    deliverRowLocked(
                        notification = record,
                        initialResolution = resolution,
                    )

                is ReaderRecord.Absent ->
                    deliverAbsentLocked(record as ReaderRecord.Absent)
            }
        }

        /** Plans a current row, reserving work before delivery and rechecking after suspension. */
        private suspend fun deliverRowLocked(
            notification: ReaderRecord<V>,
            initialResolution: ReaderResolution<V>,
        ) {
            installCompletedCommittedWaitIfNeededLocked()
            var resolution = initialResolution
            var row = resolution.record as ReaderRecord.Row<V>
            suppressMissingUntilReaderRecovery = false
            serverDeletionObserved = false

            val committedWait = awaitingCommitted
            if (committedWait != null && !isCausallyCurrent(notification, committedWait)) return
            val authorizedByCommittedWait = committedWait != null
            if (committedWait != null) clearCommittedWaitLocked(committedWait)

            val observedTicket = watchedTicket
            val observedOutcome =
                observedTicket
                    ?.takeIf { it.outcome.isCompleted }
                    ?.let { awaitTicketOutcome(it) }
            if (
                observedTicket != null &&
                row.envelope.directRevalidationOwner === observedTicket &&
                (notification as? ReaderRecord.Row<V>)?.envelope?.value == row.envelope.value
            ) {
                // A replay mapped before this collector's 304 must not replace its exact fresh
                // owner while the ordered watcher is still responsible for direct delivery.
                return
            }
            var collectorPlan =
                collectorPlanFor(
                    snapshot = row.snapshot(resolution),
                    eligibleBaseline = readerEligibleBaseline(notification, row.envelope),
                )
            var rowPlan = collectorPlan.plan
            var demandSatisfied =
                envelopeSatisfiesDemand(
                    collectorPlan.eligibleEnvelope,
                    resolution.state,
                    rowPlan,
                )
            if (demandSatisfied) {
                terminalFailedDemand = null
                lastRevalidationRequestedRevision = row.residenceRevision
            } else {
                failedDemandStillCovers(row.snapshot(resolution))
            }

            val settledTicket = observedTicket
            val completedSettledOutcome =
                observedOutcome?.takeIf { outcome ->
                    settledTicket?.let { ticket ->
                        if (outcome is FetchOutcome.Failed) {
                            residenceAdvancedFrom(
                                ticket,
                                row.snapshot(resolution),
                            )
                        } else {
                            ticket.requestRevision != row.residenceRevision
                        }
                    } == true
                }
            if (completedSettledOutcome is FetchOutcome.Deleted) {
                if (watchedTicket === settledTicket) watchedTicket = null
                handleOutcomeLocked(
                    checkNotNull(settledTicket),
                    completedSettledOutcome,
                    servedStaleForWatchedTicket,
                )
                serverDeletionObserved = false
            }

            val pendingTicket = watchedTicket
            val pendingSlot = resolution.state.fetch as? FetchSlot.InFlight
            val durableWriterRowReady =
                demandSatisfied &&
                    (
                        authorizedByCommittedWait ||
                            pendingTicket?.let {
                                isOwnDurablyCommittedRow(notification, it)
                            } == true
                    )
            if (
                pendingTicket != null &&
                pendingTicket === settledTicket &&
                observedOutcome == null &&
                pendingSlot?.ticket !== pendingTicket &&
                !durableWriterRowReady
            ) {
                // Slot settlement can precede ordered persistence/bookkeeping tails. Retain every
                // other row until the exact outcome can install its causal or replacement handoff.
                return
            }

            if (
                rowPlan !is FetchPlan.Skip &&
                !demandSatisfied &&
                settledTicket != null &&
                completedSettledOutcome != null &&
                completedSettledOutcome !is FetchOutcome.Deleted
            ) {
                val outcome = completedSettledOutcome
                if (outcome !is FetchOutcome.Committed) {
                    val oldServedStale = servedStaleForWatchedTicket
                    if (outcome is FetchOutcome.Failed) {
                        enqueueFailureHandoff(
                            SettledTicketHandoff(
                                ticket = settledTicket,
                                outcome = outcome,
                                servedStale = oldServedStale,
                            ),
                        )
                    }
                    val replacement = ensureFetchForCollector(collectorPlan)
                    if (replacement != null) {
                        watchTicketLocked(replacement)
                    } else if (watchedTicket === settledTicket) {
                        watchedTicket = null
                    }

                    // The replacement reservation can suspend. Only the exact row that caused
                    // the handoff may now be served as refreshing.
                    val current = resolveCurrentRecord(notification)
                    val currentRow = current?.record as? ReaderRecord.Row<V>
                    if (current == null || currentRow == null || !isSameResolvedRow(row, currentRow)) {
                        return
                    }
                    resolution = current
                    row = currentRow
                    collectorPlan =
                        collectorPlanFor(
                            snapshot = row.snapshot(resolution),
                            eligibleBaseline = readerEligibleBaseline(notification, row.envelope),
                        )
                    rowPlan = collectorPlan.plan
                    demandSatisfied =
                        envelopeSatisfiesDemand(
                            collectorPlan.eligibleEnvelope,
                            resolution.state,
                            rowPlan,
                        )
                    if (demandSatisfied) {
                        terminalFailedDemand = null
                        lastRevalidationRequestedRevision = row.residenceRevision
                    } else {
                        failedDemandStillCovers(row.snapshot(resolution))
                    }
                }
            }

            if (
                rowPlan !is FetchPlan.Skip &&
                !demandSatisfied &&
                watchedTicket == null &&
                lastRevalidationRequestedRevision != row.residenceRevision
            ) {
                ensureFetchForCollector(collectorPlan)?.let(::watchTicketLocked)

                // ensureFetch can suspend while another observation wins. The original row is
                // only deliverable if generation, residence revision, and content all survived.
                val current = resolveCurrentRecord(notification) ?: return
                val currentRow = current.record as? ReaderRecord.Row<V> ?: return
                if (!isSameResolvedRow(row, currentRow)) return
                resolution = current
                row = currentRow
                collectorPlan =
                    collectorPlanFor(
                        snapshot = row.snapshot(resolution),
                        eligibleBaseline = readerEligibleBaseline(notification, row.envelope),
                    )
                rowPlan = collectorPlan.plan
                demandSatisfied =
                    envelopeSatisfiesDemand(
                        collectorPlan.eligibleEnvelope,
                        resolution.state,
                        rowPlan,
                    )
                if (demandSatisfied) {
                    terminalFailedDemand = null
                    lastRevalidationRequestedRevision = row.residenceRevision
                } else {
                    failedDemandStillCovers(row.snapshot(resolution))
                }
            }

            val eligibleEnvelope = collectorPlan.eligibleEnvelope
            when {
                demandSatisfied && eligibleEnvelope != null ->
                    deliverDataLocked(
                        eligibleEnvelope,
                        revision = collectorPlan.eligibleRevision,
                        projectionBase = collectorPlan.eligibleProjectionBase,
                        authority =
                            if (eligibleEnvelope.directRevalidationOwner != null) {
                                DataDeliveryAuthority.CollectorBaseline
                            } else {
                                DataDeliveryAuthority.Generic
                            },
                    )

                rowPlan.servesResident && eligibleEnvelope != null ->
                    deliverDataLocked(
                        eligibleEnvelope,
                        revision = collectorPlan.eligibleRevision,
                        projectionBase = collectorPlan.eligibleProjectionBase,
                        authority =
                            if (eligibleEnvelope.directRevalidationOwner != null) {
                                DataDeliveryAuthority.CollectorBaseline
                            } else {
                                DataDeliveryAuthority.Generic
                            },
                    )
                else -> emitLoadingLocked()
            }
            flushPendingFailureHandoffsLocked()
        }

        /** Defers an absence already owned by an in-flight commit or authoritative delete outcome. */
        private suspend fun deliverAbsentLocked(notification: ReaderRecord.Absent) {
            val pendingTicket = watchedTicket
            // The authoritative Deleted outcome owns exact-absence projection and its terminal
            // Missing error. A restarted reader can observe the committed null first; letting that
            // record replan here would insert Loading between the optimistic projection and error.
            if (pendingTicket?.disposition?.value == FetchDisposition.Deleted) return
            if (pendingTicket != null && !pendingTicket.outcome.isCompleted) {
                val committing =
                    pendingTicket.disposition.value as? FetchDisposition.Committing
                if (
                    committing != null &&
                    notification.consumedAttribution !== committing.attribution &&
                    notification.activeWriteAttributionAtObservation !== committing.attribution
                ) {
                    return
                }
            }
            installCompletedCommittedWaitIfNeededLocked()
            val committedWait = awaitingCommitted
            if (committedWait != null && !isCausallyCurrent(notification, committedWait)) return
            if (committedWait != null) clearCommittedWaitLocked(committedWait)
            suppressMissingUntilReaderRecovery = false
            if (publicHasValue || freshness != Freshness.LocalOnly) {
                deliverAbsenceLocked(notification.residenceRevision) { emitLoadingLocked() }
            }
            if (pendingFailureHandoffs.isNotEmpty()) {
                flushPendingFailureHandoffsLocked()
            }
            failedDemandStillCovers(residenceSnapshot())
            requestAndDeliverLocked(forceRequest = false)
            flushPendingFailureHandoffsLocked()
        }

        private fun enqueueFailureHandoff(handoff: SettledTicketHandoff) {
            if (pendingFailureHandoffs.none { it.ticket === handoff.ticket }) {
                pendingFailureHandoffs.addLast(handoff)
            }
        }

        private suspend fun flushPendingFailureHandoffsLocked() {
            while (pendingFailureHandoffs.isNotEmpty()) {
                val handoff = pendingFailureHandoffs.removeFirst()
                surfaceTerminalOutcomeLocked(handoff.outcome, handoff.servedStale)
            }
        }

        private suspend fun deliverCurrentPlanStateLocked() {
            while (true) {
                val snapshot = residenceSnapshot()
                val collectorPlan = collectorPlanFor(snapshot)
                val eligibleEnvelope = collectorPlan.eligibleEnvelope
                val decision =
                    if (eligibleEnvelope != null && collectorPlan.plan.servesResident) {
                        deliverDataLocked(
                            eligibleEnvelope,
                            revision = collectorPlan.eligibleRevision,
                            projectionBase = collectorPlan.eligibleProjectionBase,
                            authority =
                                if (eligibleEnvelope.directRevalidationOwner != null) {
                                    DataDeliveryAuthority.CollectorBaseline
                                } else {
                                    DataDeliveryAuthority.Generic
                                },
                        )
                    } else if (snapshot.envelope == null && startupReaderFailure == null) {
                        deliverAbsenceLocked(snapshot.revision) { emitLoadingLocked() }
                    } else {
                        emitLoadingLocked()
                        return
                    }
                if (decision != DataDeliveryDecision.ObsoleteProjection) {
                    return
                }
            }
        }

        private fun envelopeSatisfiesDemand(
            envelope: ValueEnvelope<V>?,
            state: KeyState,
            plan: FetchPlan,
        ): Boolean {
            if (envelope == null) return false
            return when (freshness) {
                Freshness.CachedOrFetch,
                Freshness.StaleIfError,
                Freshness.MustBeFresh,
                ->
                    isEngineConfirmedEnvelope(envelope) &&
                        envelope.meta != null &&
                        envelope.staleEpochAtCommit >= state.staleEpoch

                Freshness.LocalOnly,
                is Freshness.MaxAge,
                -> plan is FetchPlan.Skip
            }
        }

        /** Authorizes only the exact writer row after durable return, never a CAS fallback. */
        private fun isOwnDurablyCommittedRow(
            notification: ReaderRecord<V>,
            ticket: FetchTicket,
        ): Boolean {
            val row = notification as? ReaderRecord.Row<V> ?: return false
            val disposition = ticket.disposition.value as? FetchDisposition.Committed
                ?: return false
            if (row.envelope.value != disposition.attribution.value) return false
            return row.consumedAttribution === disposition.attribution ||
                row.activeWriteAttributionAtObservation === disposition.attribution
        }

        /** Installs a completed commit before the current reader notification is classified. */
        private suspend fun installCompletedCommittedWaitIfNeededLocked() {
            val ticket = watchedTicket ?: return
            if (handledCommittedTicket === ticket) return
            if (!ticket.outcome.isCompleted) return
            val outcome = awaitTicketOutcome(ticket)
            if (outcome !is FetchOutcome.Committed) return

            installCommittedWaitLocked(ticket, outcome)
        }

        /** True only when this raw observation belongs at or after the completed write. */
        private fun isCausallyCurrent(
            notification: ReaderRecord<V>,
            wait: CommittedReaderWait,
        ): Boolean {
            val rawSequence =
                when (notification) {
                    is ReaderRecord.Row -> notification.rawObservationSequence
                    is ReaderRecord.Absent -> notification.rawObservationSequence
                    is ReaderRecord.Failure -> return false
                }
            if (
                notification.readerGen == wait.rawReaderGen &&
                rawSequence <= wait.rawCommitCutoff
            ) {
                return wait.authoritativeRawSequence != null &&
                    rawSequence == wait.authoritativeRawSequence
            }
            return when (notification) {
                is ReaderRecord.Row -> notification.successfulWriteSequenceAtObservation >=
                    wait.successfulWriteSequenceAtOutcome

                is ReaderRecord.Absent -> notification.successfulWriteSequenceAtObservation >=
                    wait.successfulWriteSequenceAtOutcome

                is ReaderRecord.Failure -> false
            }
        }

        private suspend fun installCommittedWaitLocked(
            ticket: FetchTicket,
            outcome: FetchOutcome.Committed,
        ) {
            installCommittedWaitLocked(
                ticket = ticket,
                successfulWriteSequence = outcome.successfulWriteSequence,
                attribution = outcome.attribution,
                rawReaderGen = outcome.rawReaderGen,
                rawCommitCutoff = outcome.rawCommitCutoff,
                authoritativeRawSequence = outcome.authoritativeRawSequence,
            )
        }

        private suspend fun installCommittedWaitLocked(
            ticket: FetchTicket,
            disposition: FetchDisposition.Committed,
        ) {
            installCommittedWaitLocked(
                ticket = ticket,
                successfulWriteSequence = disposition.successfulWriteSequence,
                attribution = disposition.attribution,
                rawReaderGen = disposition.rawReaderGen,
                rawCommitCutoff = disposition.rawCommitCutoff,
                authoritativeRawSequence = disposition.authoritativeRawSequence,
            )
        }

        private fun installCommittedWaitLocked(
            ticket: FetchTicket,
            successfulWriteSequence: Long,
            attribution: AttributionTag,
            rawReaderGen: Long,
            rawCommitCutoff: Long,
            authoritativeRawSequence: Long?,
        ) {
            handledCommittedTicket = ticket
            awaitingCommitted =
                CommittedReaderWait(
                    ticket = ticket,
                    successfulWriteSequenceAtOutcome = successfulWriteSequence,
                    attribution = attribution,
                    rawReaderGen = rawReaderGen,
                    rawCommitCutoff = rawCommitCutoff,
                    authoritativeRawSequence = authoritativeRawSequence,
                )
        }

        private suspend fun retainCommittedTicketLocked(
            ticket: FetchTicket,
            outcome: FetchOutcome.Committed,
        ) {
            if (watchedTicket !== ticket) return
            installCommittedWaitLocked(ticket, outcome)
            when (val latest = latestReaderRecord) {
                is ReaderRecord.Row,
                is ReaderRecord.Absent,
                -> deliverReaderRecordLocked(latest)

                null,
                is ReaderRecord.Failure,
                -> Unit
            }
        }

        private suspend fun reprocessLatestReaderRecordLocked() {
            when (val latest = latestReaderRecord) {
                is ReaderRecord.Row,
                is ReaderRecord.Absent,
                -> deliverReaderRecordLocked(latest)

                null,
                is ReaderRecord.Failure,
                -> Unit
            }
        }

        private fun clearCommittedWaitLocked(wait: CommittedReaderWait) {
            if (awaitingCommitted === wait) awaitingCommitted = null
            if (wait.ticket.outcome.isCompleted) {
                if (watchedTicket === wait.ticket) watchedTicket = null
                if (handledCommittedTicket === wait.ticket) handledCommittedTicket = null
            }
        }

        private suspend fun requestAndDeliverLocked(
            forceRequest: Boolean,
            suppressResidentIfVisible: Boolean = false,
        ) {
            if (awaitingCommitted != null) return
            val observedTicket = watchedTicket
            val observedOutcome =
                observedTicket
                    ?.takeIf { it.outcome.isCompleted }
                    ?.let { awaitTicketOutcome(it) }
            if (observedOutcome != null) {
                if (observedOutcome is FetchOutcome.Committed) {
                    retainCommittedTicketLocked(checkNotNull(observedTicket), observedOutcome)
                    if (awaitingCommitted != null) return
                } else {
                    return
                }
            }
            var snapshot = residenceSnapshot()
            var collectorPlan = collectorPlanFor(snapshot)
            var plan = collectorPlan.plan
            if (forceRequest) {
                terminalFailedDemand = null
            } else if (
                plan is FetchPlan.Skip ||
                envelopeSatisfiesDemand(
                    collectorPlan.eligibleEnvelope,
                    snapshot.state,
                    plan,
                )
            ) {
                terminalFailedDemand = null
            } else {
                failedDemandStillCovers(snapshot)
            }

            if (freshness == Freshness.LocalOnly) {
                val envelope = collectorPlan.eligibleEnvelope
                if (envelope != null) {
                    deliverDataLocked(
                        envelope,
                        revision = collectorPlan.eligibleRevision,
                        projectionBase = collectorPlan.eligibleProjectionBase,
                        authority =
                            if (envelope.directRevalidationOwner != null) {
                                DataDeliveryAuthority.CollectorBaseline
                            } else {
                                DataDeliveryAuthority.Generic
                            },
                    )
                } else if (!suppressMissingUntilReaderRecovery) {
                    if (overlay != null && snapshot.envelope == null) {
                        deliverAbsenceLocked(snapshot.revision) {
                            if (publicHasValue) emitLoadingLocked()
                            emitLocalOnlyMissingLocked()
                        }
                    } else {
                        if (publicHasValue) emitLoadingLocked()
                        emitLocalOnlyMissingLocked()
                    }
                }
                return
            }

            var ticket: FetchTicket? = null
            if (
                plan !is FetchPlan.Skip &&
                watchedTicket == null &&
                !(serverDeletionObserved && snapshot.envelope == null) &&
                (forceRequest || lastRevalidationRequestedRevision != snapshot.revision)
            ) {
                ticket = ensureFetchForCollector(collectorPlan)
                ticket?.let(::watchTicketLocked)
                snapshot = residenceSnapshot()
                collectorPlan = collectorPlanFor(snapshot)
                plan = collectorPlan.plan
            }

            val eligibleEnvelope = collectorPlan.eligibleEnvelope
            when {
                eligibleEnvelope != null && plan.servesResident -> {
                    val sameResidenceAlreadyVisible =
                        publicHasValue &&
                            lastDataFingerprint == DataFingerprint(eligibleEnvelope)
                    if (!suppressResidentIfVisible || !sameResidenceAlreadyVisible) {
                        deliverDataLocked(
                            eligibleEnvelope,
                            revision = collectorPlan.eligibleRevision,
                            projectionBase = collectorPlan.eligibleProjectionBase,
                            authority =
                                if (eligibleEnvelope.directRevalidationOwner != null) {
                                    DataDeliveryAuthority.CollectorBaseline
                                } else {
                                    DataDeliveryAuthority.Generic
                                },
                        )
                    } else {
                        refreshVisibleStaleOwnershipLocked()
                    }
                }

                eligibleEnvelope != null && plan is FetchPlan.Skip ->
                    deliverDataLocked(
                        eligibleEnvelope,
                        revision = collectorPlan.eligibleRevision,
                        projectionBase = collectorPlan.eligibleProjectionBase,
                        authority =
                            if (eligibleEnvelope.directRevalidationOwner != null) {
                                DataDeliveryAuthority.CollectorBaseline
                            } else {
                                DataDeliveryAuthority.Generic
                            },
                    )

                plan !is FetchPlan.Skip -> emitLoadingLocked()
                else -> emitLocalOnlyMissingLocked()
            }

            ticket?.let(::watchTicketLocked)
        }

        private fun watchTicketLocked(ticket: FetchTicket) {
            // ensureFetch may join work launched against an older residence. Associate this
            // collector with the actual ticket boundary so a newer revision can replan later.
            terminalFailedDemand = null
            lastRevalidationRequestedRevision = ticket.requestRevision
            if (watchedTicket === ticket) return
            watchedTicket = ticket
            servedStaleForWatchedTicket = publicServedStale
            observeCommittedDisposition(ticket)
            producer.launch {
                val outcome = awaitTicketOutcome(ticket)
                beforeTicketOutcomeDeliveryTestGate()
                mutex.withLock {
                    deliverWatchedOutcomeLocked(ticket, outcome)
                }
            }
        }

        /** Wakes a retained writer row at durable return, before ordered bookkeeping completes. */
        private fun observeCommittedDisposition(ticket: FetchTicket) {
            producer.launch {
                val terminal =
                    ticket.disposition.first {
                        it !is FetchDisposition.InFlight &&
                            it !is FetchDisposition.Committing
                    }
                val committed = terminal as? FetchDisposition.Committed ?: return@launch
                mutex.withLock {
                    if (
                        watchedTicket === ticket &&
                        handledCommittedTicket !== ticket
                    ) {
                        installCommittedWaitLocked(ticket, committed)
                        reprocessLatestReaderRecordLocked()
                    }
                }
            }
        }

        private suspend fun deliverWatchedOutcomeLocked(
            ticket: FetchTicket,
            outcome: FetchOutcome,
        ) {
            if (watchedTicket !== ticket) return
            if (
                outcome is FetchOutcome.Committed &&
                handledCommittedTicket === ticket
            ) {
                if (awaitingCommitted?.ticket === ticket) return
                watchedTicket = null
                reprocessLatestReaderRecordLocked()
                if (handledCommittedTicket === ticket) handledCommittedTicket = null
                return
            }
            var servedStaleForTicket = servedStaleForWatchedTicket
            if (outcome is FetchOutcome.Failed) {
                val advancedResidence = residenceAdvancedFrom(ticket, residenceSnapshot())
                if (advancedResidence) {
                    enqueueFailureHandoff(
                        SettledTicketHandoff(
                            ticket = ticket,
                            outcome = outcome,
                            servedStale = servedStaleForTicket,
                        ),
                    )
                }
                reprocessLatestReaderRecordLocked()
                if (watchedTicket !== ticket) return
                if (advancedResidence) {
                    watchedTicket = null
                    val snapshot = residenceSnapshot()
                    val collectorPlan = collectorPlanFor(snapshot)
                    val plan = collectorPlan.plan
                    if (plan !is FetchPlan.Skip) {
                        val replacement = ensureFetchForCollector(collectorPlan)
                        replacement?.let(::watchTicketLocked)
                        if (replacement == null) {
                            deliverCurrentPlanStateLocked()
                            flushPendingFailureHandoffsLocked()
                        }
                    } else {
                        flushPendingFailureHandoffsLocked()
                    }
                    return
                }
                if (pendingFailureHandoffs.isNotEmpty()) {
                    deliverCurrentPlanStateLocked()
                    flushPendingFailureHandoffsLocked()
                    servedStaleForTicket = servedStaleForWatchedTicket
                }
            }
            if (outcome !is FetchOutcome.Committed) watchedTicket = null
            handleOutcomeLocked(ticket, outcome, servedStaleForTicket)
            if (
                outcome !is FetchOutcome.Committed &&
                outcome !is FetchOutcome.Failed
            ) {
                reprocessLatestReaderRecordLocked()
                flushPendingFailureHandoffsLocked()
            }
        }

        private suspend fun handleOutcomeLocked(
            ticket: FetchTicket,
            outcome: FetchOutcome,
            servedStaleForTicket: Boolean,
        ) {
            when (outcome) {
                is FetchOutcome.Committed ->
                    retainCommittedTicketLocked(ticket, outcome)

                is FetchOutcome.Revalidated -> {
                    if (
                        deliverRevalidatedLocked(outcome, watchReplacement = true) ==
                        RevalidatedDelivery.Obsolete
                    ) {
                        requestAndDeliverLocked(forceRequest = true)
                    }
                }

                FetchOutcome.ObsoleteRevalidation ->
                    requestAndDeliverLocked(forceRequest = true)

                is FetchOutcome.Failed -> {
                    surfaceTerminalOutcomeLocked(outcome, servedStaleForTicket)
                    val snapshot = residenceSnapshot()
                    if (residenceAdvancedFrom(ticket, snapshot)) {
                        requestAndDeliverLocked(forceRequest = false)
                    } else {
                        terminalFailedDemand = ticket
                    }
                }

                is FetchOutcome.Deleted -> {
                    serverDeletionObserved = true
                    surfaceTerminalOutcomeLocked(outcome, servedStaleForTicket)
                }

                FetchOutcome.Superseded -> requestAndDeliverLocked(forceRequest = true)
            }
        }

        private suspend fun surfaceTerminalOutcomeLocked(
            outcome: FetchOutcome,
            servedStaleForTicket: Boolean,
        ) {
            when (outcome) {
                is FetchOutcome.Failed -> {
                    if (overlay != null) {
                        deliverCurrentPlanStateLocked()
                    }
                    emitErrorLocked(
                        outcome.exception,
                        servedStaleOverride =
                            if (overlay == null) servedStaleForTicket else publicServedStale,
                    )
                }

                is FetchOutcome.Deleted -> {
                    if (overlay == null) {
                        emitLoadingLocked()
                    } else {
                        val rendered =
                            deliverAbsenceLocked(outcome.residenceRevision) {
                                emitLoadingLocked()
                            }
                        if (rendered == DataDeliveryDecision.ObsoleteProjection) {
                            deliverCurrentPlanStateLocked()
                        }
                    }
                    emitErrorLocked(serverDeletedException(), servedStaleOverride = false)
                }

                else -> error("Only terminal outcomes can be surfaced by this helper: $outcome")
            }
        }

        private suspend fun deliverDataLocked(
            envelope: ValueEnvelope<V>,
            revision: Long,
            projectionBase: ProjectionBase<V>? = null,
            originOverride: Origin? = null,
            authority: DataDeliveryAuthority = DataDeliveryAuthority.Generic,
        ): DataDeliveryDecision {
            if (overlay == null) {
                return deliverConfirmedDataLocked(
                    envelope = envelope,
                    revision = revision,
                    originOverride = originOverride,
                    authority = authority,
                )
            }
            val existingAuthorization = projectionAuthorization
            if (
                envelope.directRevalidationOwner != null &&
                authority == DataDeliveryAuthority.Generic &&
                !(
                    publicHasValue &&
                        existingAuthorization?.base?.envelope === envelope &&
                        existingAuthorization.base.revision == revision
                )
            ) {
                refreshVisibleStaleOwnershipLocked()
                return DataDeliveryDecision.ForeignDirectRevalidation
            }

            beforeProjectionAuthorizationTestGate()
            val authorizationBase =
                projectionBase?.also { captured ->
                    check(captured.envelope === envelope && captured.revision == revision) {
                        "A captured projection base must name the delivered residence."
                    }
                } ?: ProjectionBase(envelope, revision)
            val authorization =
                projectionAuthorization(
                    base = authorizationBase,
                    originOverride = originOverride,
                    authority = authority,
                )
            val previousAuthorization = projectionAuthorization
            projectionAuthorization = authorization
            return when (val readiness = awaitProjectionReadiness(authorization)) {
                is ProjectionReadiness.Ready ->
                    renderProjectionLocked(
                        authorization = authorization,
                        projection = readiness.projection,
                    )

                ProjectionReadiness.Obsolete -> {
                    revokeObsoleteProjectionAuthorization(authorization, previousAuthorization)
                    DataDeliveryDecision.ObsoleteProjection
                }
            }
        }

        /** Landed confirmed-value renderer, also used by pass-through projection intent. */
        private suspend fun deliverConfirmedDataLocked(
            envelope: ValueEnvelope<V>,
            revision: Long,
            originOverride: Origin? = null,
            authority: DataDeliveryAuthority = DataDeliveryAuthority.Generic,
        ): DataDeliveryDecision {
            val fingerprint =
                DataFingerprint(
                    envelope = envelope,
                )
            if (
                envelope.directRevalidationOwner != null &&
                authority == DataDeliveryAuthority.Generic &&
                !(publicHasValue && lastDataFingerprint == fingerprint)
            ) {
                refreshVisibleStaleOwnershipLocked()
                return DataDeliveryDecision.ForeignDirectRevalidation
            }
            val snapshot = state.value
            val refreshing = snapshot.fetch is FetchSlot.InFlight
            val data =
                toData(
                    envelope = envelope,
                    freshness = freshness,
                    originOverride = originOverride,
                    refreshingOverride = refreshing,
                )
            if (lastDataFingerprint == fingerprint && publicHasValue) {
                lastConfirmedRevision = revision
                publicServedStale = data.isStale && staleServingTolerated(freshness)
                if (watchedTicket != null) {
                    servedStaleForWatchedTicket = publicServedStale
                }
                return DataDeliveryDecision.AlreadyVisible
            }
            producer.send(data)
            telemetry?.onServe(key, data.origin)
            lastDataFingerprint = fingerprint
            lastConfirmedRevision = revision
            publicHasValue = true
            loadingVisible = false
            localOnlyMissingEmitted = false
            publicServedStale = data.isStale && staleServingTolerated(freshness)
            if (watchedTicket != null) {
                servedStaleForWatchedTicket = publicServedStale
            }
            return DataDeliveryDecision.Delivered
        }

        /** Authorizes exact confirmed absence and renders only its matching ready projection. */
        private suspend fun deliverAbsenceLocked(
            revision: Long,
            absent: suspend () -> Unit,
        ): DataDeliveryDecision {
            if (overlay == null) {
                absent()
                return DataDeliveryDecision.Absent
            }
            val authorization =
                projectionAuthorization(
                    base = ProjectionBase(envelope = null, revision = revision),
                    originOverride = null,
                    authority = DataDeliveryAuthority.Generic,
                )
            val previousAuthorization = projectionAuthorization
            projectionAuthorization = authorization
            return when (val readiness = awaitProjectionReadiness(authorization)) {
                is ProjectionReadiness.Ready ->
                    renderProjectionLocked(
                        authorization = authorization,
                        projection = readiness.projection,
                    )

                ProjectionReadiness.Obsolete -> {
                    revokeObsoleteProjectionAuthorization(authorization, previousAuthorization)
                    DataDeliveryDecision.ObsoleteProjection
                }
            }
        }

        /** Builds an exact authorization and targets any already-accepted matching generation. */
        private fun projectionAuthorization(
            base: ProjectionBase<V>,
            originOverride: Origin?,
            authority: DataDeliveryAuthority,
        ): ProjectionAuthorization<V> {
            val currentBase = checkNotNull(projectionResidence).value.base
            val authorizedBase = currentBase.takeIf { it.matches(base) } ?: base
            val snapshot = checkNotNull(projectionSnapshot).value
            val targetGeneration =
                when (snapshot) {
                    is ProjectionSnapshot.Pending ->
                        snapshot.generation.takeIf { snapshot.base.matches(authorizedBase) }

                    is ProjectionSnapshot.Ready ->
                        snapshot.generation.takeIf { snapshot.base.matches(authorizedBase) }

                    is ProjectionSnapshot.Terminal -> snapshot.generation
                    ProjectionSnapshot.Uninitialized -> null
                } ?: 0L
            return ProjectionAuthorization(
                base = authorizedBase,
                originOverride = originOverride,
                authority = authority,
                targetGeneration = targetGeneration,
            )
        }

        /** Waits on snapshot and residence flows only; no engine lock is reacquired. */
        private suspend fun awaitProjectionReadiness(
            authorization: ProjectionAuthorization<V>,
        ): ProjectionReadiness<V> {
            val snapshots = checkNotNull(projectionSnapshot)
            val residences = checkNotNull(projectionResidence)
            while (true) {
                val residence = residences.value
                if (!residence.base.matches(authorization.base)) {
                    return ProjectionReadiness.Obsolete
                }
                val snapshot = snapshots.value
                when (snapshot) {
                    is ProjectionSnapshot.Terminal ->
                        throw OverlayProjectionException(snapshot.failure)

                    is ProjectionSnapshot.Pending ->
                        if (snapshot.base.matches(authorization.base)) {
                            authorization.targetGeneration =
                                maxOf(authorization.targetGeneration, snapshot.generation)
                        }

                    is ProjectionSnapshot.Ready ->
                        if (
                            snapshot.base.matches(authorization.base) &&
                            snapshot.generation >= authorization.targetGeneration
                        ) {
                            authorization.targetGeneration =
                                maxOf(authorization.targetGeneration, snapshot.generation)
                            return ProjectionReadiness.Ready(snapshot.projection)
                        }

                    ProjectionSnapshot.Uninitialized -> Unit
                }

                val observedSnapshot = snapshot
                val observedResidence = residence
                beforeProjectionReadinessWaitTestGate()
                combine(snapshots, residences) { nextSnapshot, nextResidence ->
                    nextSnapshot to nextResidence
                }.first { (nextSnapshot, nextResidence) ->
                    nextSnapshot !== observedSnapshot || nextResidence !== observedResidence
                }
            }
        }

        /** Applies one projection through the collector's retained policy/render context. */
        private suspend fun renderProjectionLocked(
            authorization: ProjectionAuthorization<V>,
            projection: Projection<V>,
        ): DataDeliveryDecision =
            when (projection) {
                is Projection.Value ->
                    deliverConfirmedDataLocked(
                        envelope = checkNotNull(authorization.base.envelope),
                        revision = authorization.base.revision,
                        originOverride = authorization.originOverride,
                        authority = authorization.authority,
                    )

                is Projection.Overlaid -> renderOverlaidLocked(authorization, projection.value)
                Projection.Absent -> {
                    emitLoadingLocked(preserveProjectionAuthorization = true)
                    DataDeliveryDecision.Absent
                }
            }

        /** Renders overlay-created data and derives stale-error posture from the authorized base. */
        private suspend fun renderOverlaidLocked(
            authorization: ProjectionAuthorization<V>,
            value: V,
        ): DataDeliveryDecision {
            val fingerprint =
                DataFingerprint(
                    envelope = authorization.base.envelope,
                    overlaidValue = value,
                    isOverlaid = true,
                )
            val baseServedStale =
                authorization.base.envelope?.let { envelope ->
                    toData(
                        envelope = envelope,
                        freshness = freshness,
                        originOverride = authorization.originOverride,
                    ).isStale && staleServingTolerated(freshness)
                } == true
            val samePublicProjection =
                publicHasValue &&
                    lastDataFingerprint?.isOverlaid == true &&
                    lastDataFingerprint?.overlaidValue == value
            if (samePublicProjection) {
                lastDataFingerprint = fingerprint
                lastConfirmedRevision = authorization.base.revision
                publicServedStale = baseServedStale
                if (watchedTicket != null) servedStaleForWatchedTicket = publicServedStale
                return DataDeliveryDecision.AlreadyVisible
            }
            val data =
                StoreResult.Data(
                    value = value,
                    origin = Origin.OVERLAY,
                    age = Duration.ZERO,
                    isStale = false,
                    refreshing = state.value.fetch is FetchSlot.InFlight,
                )
            producer.send(data)
            telemetry?.onServe(key, Origin.OVERLAY)
            lastDataFingerprint = fingerprint
            lastConfirmedRevision = authorization.base.revision
            publicHasValue = true
            loadingVisible = false
            localOnlyMissingEmitted = false
            publicServedStale = baseServedStale
            if (watchedTicket != null) servedStaleForWatchedTicket = publicServedStale
            return DataDeliveryDecision.Delivered
        }

        /** Reprojects an active authorization or applies the referential foreign-owner exception. */
        private suspend fun deliverProjectionSnapshotLocked(snapshot: ProjectionSnapshot<V>) {
            if (checkNotNull(projectionSnapshot).value !== snapshot) return
            if (snapshot is ProjectionSnapshot.Terminal) {
                throw OverlayProjectionException(snapshot.failure)
            }
            val ready = snapshot as? ProjectionSnapshot.Ready<V> ?: return
            val authorization = projectionAuthorization ?: return
            val currentBase = checkNotNull(projectionResidence).value.base
            if (!ready.base.matches(currentBase)) return
            if (ready.generation < authorization.targetGeneration) return
            when {
                ready.base.matches(authorization.base) ->
                    renderProjectionLocked(authorization, ready.projection)

                ready.base.isConfirmFreshAuthorizationSuccessorOf(authorization.base) -> {
                    val successorAuthorization =
                        ProjectionAuthorization(
                            base = ready.base,
                            originOverride = authorization.originOverride,
                            authority = authorization.authority,
                            targetGeneration =
                                maxOf(authorization.targetGeneration, ready.generation),
                        )
                    projectionAuthorization = successorAuthorization
                    renderProjectionLocked(successorAuthorization, ready.projection)
                }

                publicHasValue &&
                    ready.base.envelope?.directRevalidationOwner != null &&
                    authorization.base.envelope != null &&
                    ready.base.envelope.value === authorization.base.envelope.value ->
                    renderProjectionLocked(authorization, ready.projection)
            }
        }

        private fun revokeObsoleteProjectionAuthorization(
            authorization: ProjectionAuthorization<V>,
            previousAuthorization: ProjectionAuthorization<V>?,
        ) {
            if (projectionAuthorization !== authorization) return
            val currentBase = checkNotNull(projectionResidence).value.base
            if (currentBase.isConfirmFreshAuthorizationSuccessorOf(authorization.base)) return
            val current = currentBase.envelope
            projectionAuthorization =
                previousAuthorization?.takeIf { previous ->
                    current?.directRevalidationOwner != null &&
                        publicHasValue &&
                        lastDataFingerprint?.envelope === previous.base.envelope &&
                        lastConfirmedRevision == previous.base.revision
                }
        }

        private fun refreshVisibleStaleOwnershipLocked() {
            val visibleEnvelope = lastDataFingerprint?.envelope
            publicServedStale =
                publicHasValue &&
                    visibleEnvelope != null &&
                    toData(
                        envelope = visibleEnvelope,
                        freshness = freshness,
                    ).isStale &&
                    staleServingTolerated(freshness)
            if (watchedTicket != null) {
                servedStaleForWatchedTicket = publicServedStale
            }
        }

        private suspend fun emitLoadingLocked(
            preserveProjectionAuthorization: Boolean = false,
        ) {
            if (!preserveProjectionAuthorization) projectionAuthorization = null
            if (loadingVisible) return
            producer.send(StoreResult.Loading())
            loadingVisible = true
            publicHasValue = false
            publicServedStale = false
            servedStaleForWatchedTicket = false
            lastDataFingerprint = null
        }

        private suspend fun emitLocalOnlyMissingLocked() {
            if (localOnlyMissingEmitted) return
            producer.send(
                StoreResult.Error(
                    error = localOnlyMissingException().error,
                    servedStale = false,
                ),
            )
            localOnlyMissingEmitted = true
            publicHasValue = false
            loadingVisible = false
            publicServedStale = false
            servedStaleForWatchedTicket = false
            lastDataFingerprint = null
        }

        private suspend fun emitErrorLocked(
            exception: StoreException,
            servedStaleOverride: Boolean? = null,
        ) {
            producer.send(
                StoreResult.Error(
                    error = exception.error,
                    servedStale = servedStaleOverride ?: publicServedStale,
                ),
            )
        }
    }

    /** Returns a value according to policy, hydrating persistence before planning on a miss. */
    internal suspend fun get(freshness: Freshness): V {
        ensureOpen()
        while (true) {
            var snapshot = residenceSnapshot()
            if (snapshot.envelope == null) snapshot = hydrateFromSot()
            val envelope = snapshot.envelope
            val plan = planFor(freshness, snapshot)

            if (plan is FetchPlan.Skip) {
                return envelope?.let { serve(it.value, it.origin) }
                    ?: throw localOnlyMissingException()
            }

            if (
                envelope != null &&
                plan.servesResident &&
                freshness != Freshness.StaleIfError
            ) {
                ensureFetch(freshness)
                return serve(envelope.value, envelope.origin)
            }

            val ticket = ensureFetch(freshness) ?: continue
            val outcome = ticket.outcome.await()
            beforeTicketOutcomeDeliveryTestGate()
            when (outcome) {
                is FetchOutcome.Committed ->
                    return serve(committedValue(outcome), outcome.attribution.origin)

                is FetchOutcome.Revalidated -> {
                    val snapshot = residenceSnapshot()
                    if (snapshot.envelope === outcome.envelope) {
                        val plan =
                            planFor(
                                freshness = freshness,
                                snapshot = snapshot,
                            )
                        if (revalidatedSatisfiesDemand(freshness, snapshot, plan)) {
                            snapshot.envelope?.let { return serve(it.value, it.origin) }
                        }
                    }
                }

                FetchOutcome.ObsoleteRevalidation -> Unit

                is FetchOutcome.Failed ->
                    if (freshness == Freshness.StaleIfError && envelope != null) {
                        return serve(envelope.value, envelope.origin)
                    } else {
                        throw outcome.exception
                    }

                FetchOutcome.Superseded -> throw supersededException()
                is FetchOutcome.Deleted -> throw serverDeletedException()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun committedValue(outcome: FetchOutcome.Committed): V = outcome.value as V

    /** Reports one successful caller-context serve without altering the returned value. */
    private fun serve(
        value: V,
        origin: Origin,
    ): V {
        telemetry?.onServe(key, origin)
        return value
    }

    /** Renders nullable metadata conservatively while retaining saturating age behavior. */
    private fun toData(
        envelope: ValueEnvelope<V>,
        freshness: Freshness,
        originOverride: Origin? = null,
        refreshingOverride: Boolean? = null,
    ): StoreResult.Data<V> {
        val snapshot = state.value
        val meta = envelope.meta
        val age = elapsedAge(wallClock.nowEpochMillis(), meta)
        val epochStale = envelope.staleEpochAtCommit < snapshot.staleEpoch
        val ageStale = freshness is Freshness.MaxAge && meta != null && age > freshness.notOlderThan
        return StoreResult.Data(
            value = envelope.value,
            origin = originOverride ?: envelope.origin,
            age = age,
            isStale = meta == null || epochStale || ageStale,
            refreshing = refreshingOverride ?: (snapshot.fetch is FetchSlot.InFlight),
        )
    }

    private fun fetchException(failure: Throwable): StoreException {
        val message =
            "Fetch failed for key '${keyId.namespace}/${keyId.canonicalId}': ${failure.message}. " +
                "The fetcher threw; inspect the cause for the underlying failure."
        return StoreException(StoreError.Fetch(message, failure), failure)
    }

    private fun fetchResultException(failure: Throwable): StoreException {
        val message =
            "Fetch failed for key '${keyId.namespace}/${keyId.canonicalId}': ${failure.message}. " +
                "The fetcher returned FetcherResult.Error; inspect the cause for the " +
                "underlying failure."
        return StoreException(StoreError.Fetch(message, failure), failure)
    }

    private fun readerException(failure: Throwable): StoreException {
        val message =
            "Reading the source of truth failed for key " +
                "'${keyId.namespace}/${keyId.canonicalId}': ${failure.message}. " +
                "Durable data could not be observed; inspect the cause and retry the read."
        return StoreException(StoreError.Persistence(message, failure), failure)
    }

    private fun writeException(failure: Throwable): StoreException {
        val message =
            "Persisting the fetched value failed for key " +
                "'${keyId.namespace}/${keyId.canonicalId}': ${failure.message}. " +
                "The fetch succeeded but the source of truth rejected the write; inspect the " +
                "cause and retry the read."
        return StoreException(StoreError.Persistence(message, failure), failure)
    }

    private fun writeHandleException(failure: Throwable): StoreException {
        val message =
            "Write-handle apply failed for key '${keyId.namespace}/${keyId.canonicalId}': " +
                "${failure.message}. The source of truth rejected the write; state is unchanged. " +
                "Inspect the cause and retry."
        return StoreException(
            error = StoreError.Persistence(message = message, cause = failure),
            cause = failure,
        )
    }

    private fun clearPersistenceException(failure: Throwable): StoreException {
        val message =
            "clear() failed for key '${keyId.namespace}/${keyId.canonicalId}': the source of " +
                "truth delete threw: ${failure.message}. The row may still exist; inspect the " +
                "cause and retry clear()."
        return StoreException(StoreError.Persistence(message, failure), failure)
    }

    /** Wraps a durable per-key maintenance failure with typed persistence context. */
    private fun maintenancePersistenceException(
        operation: String,
        failure: Throwable,
    ): StoreException {
        val message =
            "Durable $operation failed for key '${keyId.namespace}/${keyId.canonicalId}': " +
                "${failure.message}. The operation did not complete; retry it and inspect the " +
                "cause for the underlying persistence failure."
        return StoreException(
            error = StoreError.Persistence(message = message, cause = failure),
            cause = failure,
        )
    }

    private fun serverDeletePersistenceException(failure: Throwable): StoreException {
        val message =
            "Applying the server-side deletion failed for key " +
                "'${keyId.namespace}/${keyId.canonicalId}': the source of truth delete threw: " +
                "${failure.message}. The row may still exist; the deletion was not applied — " +
                "retry the read."
        return StoreException(StoreError.Persistence(message, failure), failure)
    }

    private fun supersededException(): StoreException {
        val message =
            "Could not return a value for key '${keyId.namespace}/${keyId.canonicalId}': clear() " +
                "removed the key while its fetch was in flight, so the fetched value was " +
                "discarded. The value is currently missing; retry the read to trigger a fresh " +
                "fetch."
        return StoreException(StoreError.Missing(key, message))
    }

    private fun serverDeletedException(): StoreException {
        val message =
            "Could not return a value for key '${keyId.namespace}/${keyId.canonicalId}': the " +
                "fetcher reported that the server deleted this value, so the local copy was " +
                "removed. Recreate the value upstream or treat Missing as the empty state."
        return StoreException(StoreError.Missing(key, message))
    }

    private fun localOnlyMissingException(): StoreException {
        val message =
            "Could not return a value for key '${keyId.namespace}/${keyId.canonicalId}': " +
                "Freshness.LocalOnly forbids fetching and no local value exists. Seed the key " +
                "with another policy first or handle StoreError.Missing as the empty state."
        return StoreException(StoreError.Missing(key, message))
    }

    private fun notModifiedWithoutValueException(): StoreException {
        val message =
            "Could not return a value for key '${keyId.namespace}/${keyId.canonicalId}': the " +
                "fetcher returned FetcherResult.NotModified but no local value exists to " +
                "revalidate. Return FetcherResult.Success with a full value when the client has " +
                "no cached copy."
        return StoreException(StoreError.Missing(key, message))
    }

    private fun ensureOpen() {
        if (!engineJob.isActive) throw storeClosedException()
    }

    private data class ResidenceSnapshot<V : Any>(
        val state: KeyState,
        val envelope: ValueEnvelope<V>?,
        val revision: Long,
        val status: KeyStatus?,
        val nowEpochMillis: Long,
        val projectionBase: ProjectionBase<V>?,
    )

    private data class PlannedFetchEffect<V : Any>(
        val effect: KeyEffect,
        val collectorEligibleResidence: ValueEnvelope<V>?,
        val collectorEligibleRevision: Long,
        val plan: FetchPlan,
    )

    private data class FetchReservation<V : Any>(
        val ticket: FetchTicket,
        val collectorEligibleResidence: ValueEnvelope<V>?,
        val collectorEligibleRevision: Long,
        val plan: FetchPlan,
    )

    /** Marks an engine-side raw-stamping defect so the adapter retry boundary rethrows it. */
    private class RawObservationFailure(
        val engineFailure: Throwable,
    ) : RuntimeException(engineFailure)

    /** Restarts the sole reader after discarding one unresolved committed-fence mismatch. */
    private class RestartRawReaderSession : RuntimeException()

    /** Converts self-originated flow cancellation into a terminal no-failure-contract breach. */
    private class ProjectionChangesFailure(
        val projectionCause: CancellationException,
    ) : RuntimeException(projectionCause)

    /** Raw observations made while one exact SoT write is active. */
    private sealed interface ActiveRawPhase {
        data object Unobserved : ActiveRawPhase

        class OtherBeforeMatching(
            val observation: RawWriteObservation,
        ) : ActiveRawPhase

        class Matching(
            val observation: RawWriteObservation,
            val attribution: AttributionTag,
        ) : ActiveRawPhase

        class OtherAfterMatching(
            val matchingObservation: RawWriteObservation,
            val observation: RawWriteObservation,
        ) : ActiveRawPhase
    }

    /** One source-ordered nullable adapter row captured before pipeline conflation. */
    private data class RawWriteObservation(
        val readerGen: Long,
        val rawSequence: Long,
        val value: Any?,
        val attributionAtObservation: AttributionTag?,
        val activeWriteAttributionAtObservation: AttributionTag?,
        val successfulWriteSequenceAtObservation: Long,
    ) {
        /** Returns only the exact active writer tag under the value-bound fallback rule. */
        fun matchingWriterAttribution(): AttributionTag? {
            val active = activeWriteAttributionAtObservation ?: return null
            val observed = attributionAtObservation
            return when {
                value == null -> null
                observed != null ->
                    active.takeIf { observed === active && observed.value == value }
                active.value == value -> active
                else -> null
            }
        }
    }

    private fun ActiveRawPhase.matchingObservationOrNull(): RawWriteObservation? =
        when (this) {
            ActiveRawPhase.Unobserved -> null
            is ActiveRawPhase.OtherBeforeMatching -> null
            is ActiveRawPhase.Matching -> observation
            is ActiveRawPhase.OtherAfterMatching -> matchingObservation
        }

    private data class WriteObservationBoundary(
        val readerGen: Long,
        val observedAttribution: AttributionTag?,
        val activeAttribution: AttributionTag?,
        val successfulSequence: Long,
        val latestRawSequence: Long,
        val activeRawPhase: ActiveRawPhase,
        val readerSession: Long,
        val readerSessionActive: Boolean,
        val pendingWriteAttribution: AttributionTag?,
    )

    private data class ClosedWriteBoundary(
        val readerGen: Long,
        val rawCommitCutoff: Long,
        val phase: ActiveRawPhase,
        val successfulWriteSequence: Long,
    )

    private data class DurableWriteResolution(
        val successfulWriteSequence: Long,
        val readerGen: Long,
        val rawCommitCutoff: Long,
        val authoritativeRawSequence: Long?,
    )

    private data class RawCommitResolution<V : Any>(
        val readerGen: Long,
        val rawCommitCutoff: Long,
        val authoritativeRawSequence: Long?,
        val residenceRevision: Long,
        val envelope: ValueEnvelope<V>?,
        val consumedAttribution: AttributionTag?,
    )

    private data class PreparedReaderRow(
        val consumedAttribution: AttributionTag?,
        val ownerAttribution: AttributionTag,
        val matchingAttribution: AttributionTag?,
        val dropNonmatchingOnCommit: Boolean,
    )

    private data class ReaderResolution<V : Any>(
        val record: ReaderRecord<V>,
        val state: KeyState,
        val status: KeyStatus?,
        val nowEpochMillis: Long,
        val projectionBase: ProjectionBase<V>?,
    )

    private data class InitialDelivery<V : Any>(
        val snapshot: ResidenceSnapshot<V>,
        val plan: FetchPlan,
        val ticket: FetchTicket?,
    )

    private data class CollectorFetchPlan<V : Any>(
        val eligibleEnvelope: ValueEnvelope<V>?,
        val eligibleRevision: Long,
        val plan: FetchPlan,
        val currentIsForeignOwner: Boolean,
        val eligibleProjectionBase: ProjectionBase<V>? = null,
    )

    private data class TicketLaunchBaseline<V : Any>(
        val envelope: ValueEnvelope<V>?,
        val revision: Long,
        val plan: FetchPlan,
    )

    private data class EligibleBaseline<V : Any>(
        val envelope: ValueEnvelope<V>?,
        val revision: Long,
    )

    private class ProjectionAuthorization<V : Any>(
        val base: ProjectionBase<V>,
        val originOverride: Origin?,
        val authority: DataDeliveryAuthority,
        var targetGeneration: Long,
    )

    private data class TicketLaunchBaselineEntry<V : Any>(
        val ticket: FetchTicket,
        val baseline: TicketLaunchBaseline<V>,
    )

    private enum class DataDeliveryAuthority {
        Generic,
        CollectorBaseline,
        OwnerOutcome,
    }

    private enum class DataDeliveryDecision {
        Delivered,
        AlreadyVisible,
        ForeignDirectRevalidation,
        Absent,
        ObsoleteProjection,
    }

    private sealed interface ProjectionReadiness<out V : Any> {
        class Ready<V : Any>(
            val projection: Projection<V>,
        ) : ProjectionReadiness<V>

        data object Obsolete : ProjectionReadiness<Nothing>
    }

    private sealed interface RevalidatedDelivery {
        data object Delivered : RevalidatedDelivery

        data object Obsolete : RevalidatedDelivery

        data class Replacement(
            val ticket: FetchTicket,
            val publicDeliveryCompleted: Boolean,
        ) : RevalidatedDelivery
    }

    private data class SettledTicketHandoff(
        val ticket: FetchTicket,
        val outcome: FetchOutcome,
        val servedStale: Boolean,
    )

    private data class CommittedReaderWait(
        val ticket: FetchTicket,
        val successfulWriteSequenceAtOutcome: Long,
        val attribution: AttributionTag,
        val rawReaderGen: Long,
        val rawCommitCutoff: Long,
        val authoritativeRawSequence: Long?,
    )

    private data class DataFingerprint<V : Any>(
        val envelope: ValueEnvelope<V>?,
        val overlaidValue: V? = null,
        val isOverlaid: Boolean = false,
    )
}

/** Opaque causal identity preserved only across metadata-only residence successors. */
private class ProjectionAuthorizationLineage

/** Exact residence identity and revision used by one projection attempt. */
private class ProjectionBase<V : Any>(
    val envelope: ValueEnvelope<V>?,
    val revision: Long,
    val authorizationLineage: ProjectionAuthorizationLineage? = null,
) {
    fun matches(other: ProjectionBase<V>): Boolean =
        envelope === other.envelope && revision == other.revision

    fun isConfirmFreshAuthorizationSuccessorOf(previous: ProjectionBase<V>): Boolean {
        val currentEnvelope = envelope ?: return false
        val previousEnvelope = previous.envelope ?: return false
        val lineage = authorizationLineage ?: return false
        if (lineage !== previous.authorizationLineage) return false
        if (currentEnvelope === previousEnvelope) return false
        if (revision <= previous.revision) return false
        if (currentEnvelope.value != previousEnvelope.value) return false
        if (currentEnvelope.origin != previousEnvelope.origin) return false
        if (currentEnvelope.meta == null || currentEnvelope.meta === previousEnvelope.meta) {
            return false
        }
        if (currentEnvelope.staleEpochAtCommit < previousEnvelope.staleEpochAtCommit) return false
        return currentEnvelope.directRevalidationOwner == null
    }
}

/** Immediate latest-residence signal used to obsolete readiness waits without an engine lock. */
private class ProjectionResidence<V : Any>(
    val base: ProjectionBase<V>,
)

/** Latest state of the one engine-owned projection writer. */
private sealed interface ProjectionSnapshot<out V : Any> {
    data object Uninitialized : ProjectionSnapshot<Nothing>

    class Pending<V : Any>(
        val base: ProjectionBase<V>,
        val generation: Long,
    ) : ProjectionSnapshot<V>

    class Ready<V : Any>(
        val base: ProjectionBase<V>,
        val generation: Long,
        val projection: Projection<V>,
    ) : ProjectionSnapshot<V>

    class Terminal(
        val generation: Long,
        val failure: Throwable,
    ) : ProjectionSnapshot<Nothing>
}

/** MEMORY is honest only while the exact envelope and its monotone revision remain current. */
internal fun <V : Any> canRestampMemoryOrigin(
    memoryEnvelope: ValueEnvelope<V>?,
    memoryRevision: Long,
    currentEnvelope: ValueEnvelope<V>?,
    currentRevision: Long,
): Boolean =
    memoryEnvelope != null &&
        currentEnvelope === memoryEnvelope &&
        currentRevision == memoryRevision
