@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationAliasState
import org.mobilenativefoundation.store6.mutations.storage.MutationAckRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationAttemptRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationClientRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationEffectRecord as StoredEffectRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionPhase as StoredExecutionPhase
import org.mobilenativefoundation.store6.mutations.storage.MutationExecutionRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationFailureRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyAliasRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationKeyTombstoneRecord
import org.mobilenativefoundation.store6.mutations.storage.MutationIntentRecord

internal data class KeyIdentity(
    val namespace: String,
    val canonicalId: String,
)

internal fun StoreKey.identity(): KeyIdentity = KeyIdentity(namespace.value, canonicalId())

/** R-0 §7's effect kind, in-memory form; 022 owns the durable stable names. */
internal enum class MutationEffectRecordKind {
    KEY,
    NAMESPACE,
}

/**
 * One normalized in-memory invalidation-effect target (R-0 §7's immutable snapshot shape).
 *
 * 021 captures these before first push and never executes them; execution, dispositions, and
 * durability are 022/023/024's.
 */
internal data class MutationEffectRecord(
    val kind: MutationEffectRecordKind,
    val namespace: String,
    val canonicalId: String?,
)

/**
 * Copies, normalizes, deduplicates, and sorts a consumer [StaleSet] into immutable effect
 * records (D8): every key is normalized to its full identity pair; ordering is namespace effects
 * first, then key effects, each sorted by namespace then canonical id. Equal inputs produce
 * structurally equal outputs.
 */
internal fun <K : StoreKey> normalizedMutationEffects(
    staleSet: StaleSet<K>,
): List<MutationEffectRecord> {
    val namespaceRecords =
        staleSet.namespaces
            .map { namespace ->
                MutationEffectRecord(
                    kind = MutationEffectRecordKind.NAMESPACE,
                    namespace = namespace.value,
                    canonicalId = null,
                )
            }
            .distinct()
            .sortedBy { record -> record.namespace }
    val keyRecords =
        staleSet.keys
            .map { key ->
                MutationEffectRecord(
                    kind = MutationEffectRecordKind.KEY,
                    namespace = key.namespace.value,
                    canonicalId = key.canonicalId(),
                )
            }
            .distinct()
            .sortedWith(compareBy({ record -> record.namespace }, { record -> record.canonicalId }))
    return namespaceRecords + keyRecords
}

/**
 * R-0 §3's execution phase vocabulary, in-memory form; 022 owns the durable stable names.
 *
 * At 021 the engine's foreground pass truthfully produces only `UNPREPARED`, `READY`,
 * `INFLIGHT`, and `ACKED` (plus removal on retirement). `REFRESH_REQUIRED` needs 023's conflict
 * pipeline, `EFFECTS_PENDING` needs 022/023's effect execution, and `PARKED` needs 023's parking
 * transaction — none of which 021 fakes. The total public mapping is nevertheless frozen here so
 * inspection shapes are proven against the ruled vocabulary (D3, R-0 §3).
 */
internal enum class MutationExecutionPhase {
    UNPREPARED,
    READY,
    INFLIGHT,
    REFRESH_REQUIRED,
    ACKED,
    EFFECTS_PENDING,
    PARKED,
    RETIRED,
}

/**
 * D3's total public mapping. `PARKED` maps only to `deadLetters()` and `RETIRED` to neither
 * inspection API, so both return null here; every nonterminal active phase maps to exactly one
 * [MutationPendingState].
 */
internal fun MutationExecutionPhase.toPendingStateOrNull(): MutationPendingState? =
    when (this) {
        MutationExecutionPhase.UNPREPARED,
        MutationExecutionPhase.READY,
        -> MutationPendingState.PENDING
        MutationExecutionPhase.INFLIGHT -> MutationPendingState.INFLIGHT
        MutationExecutionPhase.REFRESH_REQUIRED -> MutationPendingState.REFRESHING
        MutationExecutionPhase.ACKED -> MutationPendingState.ADOPTING
        MutationExecutionPhase.EFFECTS_PENDING -> MutationPendingState.APPLYING_EFFECTS
        MutationExecutionPhase.PARKED,
        MutationExecutionPhase.RETIRED,
        -> null
    }

/**
 * One journalled intent. [clientSequence] is the durable per-client FIFO/watermark unit and
 * [createdAtEpochMillis] the durable enqueue stamp (R-0 §2); the engine allocates both at
 * enqueue. Defaults exist only for direct journal construction in module tests.
 */
internal class JournalEntry<V : Any>(
    val mutationId: String,
    val mutatorId: String,
    val args: Any,
    val clientSequence: Long = 0L,
    val createdAtEpochMillis: Long = 0L,
    internal val durableClientSequence: Long = clientSequence,
)

/** One immutable process-cache view: projection membership and alias routing move together. */
internal data class MutationRuntimeSnapshot<V : Any>(
    val entries: Map<KeyIdentity, List<JournalEntry<V>>> = emptyMap(),
    val aliases: Map<KeyIdentity, AliasEdge> = emptyMap(),
)

/** Shared synchronization and publication state for the journal cache and alias router. */
internal class MutationRuntimeState<V : Any> {
    internal val mutex = Mutex()
    internal val snapshots = MutableStateFlow(MutationRuntimeSnapshot<V>())

    internal fun aliasesSnapshot(): Map<KeyIdentity, AliasEdge> = snapshots.value.aliases

    internal fun updateAliases(
        transform: (Map<KeyIdentity, AliasEdge>) -> Map<KeyIdentity, AliasEdge>,
    ) {
        snapshots.update { current ->
            current.copy(aliases = transform(current.aliases))
        }
    }
}

internal interface MutationJournal<V : Any> {
    suspend fun append(
        key: KeyIdentity,
        entry: JournalEntry<V>,
    ): String

    suspend fun retire(
        key: KeyIdentity,
        mutationId: String,
    )

    /** Rehomes a pending cache entry after durable alias activation without reinserting intent. */
    suspend fun rehome(
        from: KeyIdentity,
        to: KeyIdentity,
        entry: JournalEntry<V>,
    ) {
        retire(from, entry.mutationId)
        append(to, entry)
    }

    fun pendingSnapshot(key: KeyIdentity): List<JournalEntry<V>>

    /**
     * The durable identities that currently hold pending intents, in first-enqueue order (D12):
     * global drain enumerates these and reconstructs each `K` through the resolver; a live key
     * map is never this method's substitute.
     */
    fun identities(): Set<KeyIdentity>
}

/** One internally copied view of all nine durable record groups for a single client. */
internal class DurableJournalSnapshot(
    val client: MutationClientRecord?,
    val intents: List<MutationIntentRecord>,
    val executions: List<MutationExecutionRecord>,
    val attempts: List<MutationAttemptRecord>,
    val acks: List<MutationAckRecord>,
    val failures: List<MutationFailureRecord>,
    val effects: List<StoredEffectRecord>,
    val aliases: List<MutationKeyAliasRecord>,
    val tombstones: List<MutationKeyTombstoneRecord>,
)

/**
 * Cache-fronted adapter over the public durable storage seam.
 *
 * Synchronous overlay reads use the cache; every accepted append first commits the immutable
 * intent and its initial execution row to [storage], then publishes the same entry to the cache.
 * T2.4 hydrates this cache and owns the remaining execution-state persistence.
 */
internal open class StorageBackedMutationJournal<V : Any>(
    internal val storage: MutationJournalStorage,
    private val registrations: Map<String, MutatorRegistration<*, V>> = emptyMap(),
    private val clientId: String = "client-0",
    internal val hydrateOnFirstUse: Boolean = false,
) : MutationJournal<V> {
    internal val runtimeState = MutationRuntimeState<V>()

    override suspend fun append(
        key: KeyIdentity,
        entry: JournalEntry<V>,
    ): String =
        runtimeState.mutex.withLock {
            val registration = registrations[entry.mutatorId]
            val argsVersion = registration?.argsVersion ?: 1
            val argsBlob = registration?.encodeArgs(entry.args) ?: ByteArray(0)
            // The durable bytes define the accepted intent. Decode that exact snapshot before
            // committing so the live cache never retains a caller-owned mutable args object.
            val acceptedArgs = registration?.decodeArgs(argsVersion, argsBlob) ?: entry.args
            val storedEntry: JournalEntry<V> =
                storage.transaction { transaction ->
                    val currentClient =
                        transaction.client(clientId)
                            ?: MutationClientRecord(
                                recordVersion = 1,
                                clientId = clientId,
                                lastAllocatedSequence = 0L,
                                retiredThroughSequence = 0L,
                                serverConfirmedRetiredThroughSequence = 0L,
                                createdAt = entry.createdAtEpochMillis,
                            ).also(transaction::insertClient)
                    val sequence =
                        if (entry.clientSequence > 0L) {
                            entry.clientSequence
                        } else {
                            currentClient.lastAllocatedSequence + 1L
                        }
                    require(sequence == currentClient.lastAllocatedSequence + 1L) {
                        "Mutation sequence $sequence must immediately follow " +
                            "${currentClient.lastAllocatedSequence}."
                    }
                    transaction.advanceClient(
                        MutationClientRecord(
                            recordVersion = currentClient.recordVersion,
                            clientId = clientId,
                            lastAllocatedSequence = sequence,
                            retiredThroughSequence = currentClient.retiredThroughSequence,
                            serverConfirmedRetiredThroughSequence =
                                currentClient.serverConfirmedRetiredThroughSequence,
                            createdAt = currentClient.createdAt,
                        ),
                    )
                    transaction.insertIntent(
                        recordVersion = 1,
                        clientId = clientId,
                        clientSequence = sequence,
                        mutationId = entry.mutationId,
                        namespace = key.namespace,
                        canonicalId = key.canonicalId,
                        mutatorId = entry.mutatorId,
                        mutatorVersion = argsVersion,
                        argsBlob = argsBlob,
                        idempotencyRoot = "$clientId:$sequence",
                        createdAt = entry.createdAtEpochMillis,
                    )
                    transaction.insertExecution(
                        MutationExecutionRecord(
                            clientId = clientId,
                            clientSequence = sequence,
                            phase = StoredExecutionPhase.UNPREPARED,
                            currentGeneration = 0,
                            attempt = 0,
                            lastAttemptAt = null,
                            activeFailureId = null,
                            retiredAt = null,
                        ),
                    )
                    JournalEntry<V>(
                        mutationId = entry.mutationId,
                        mutatorId = entry.mutatorId,
                        args = acceptedArgs,
                        // Direct internal-shape tests intentionally use sequence zero. Persist a
                        // legal allocated sequence while retaining their caller-visible cache
                        // shape; factory-created entries already carry the same positive value.
                        clientSequence = entry.clientSequence,
                        createdAtEpochMillis = entry.createdAtEpochMillis,
                        durableClientSequence = sequence,
                    )
                }
            runtimeState.snapshots.update { current ->
                current.copy(
                    entries =
                        current.entries +
                            (key to current.entries[key].orEmpty() + storedEntry),
                )
            }
            storedEntry.mutationId
        }

    override suspend fun retire(
        key: KeyIdentity,
        mutationId: String,
    ) {
        runtimeState.mutex.withLock {
            runtimeState.snapshots.update { current ->
                val remaining =
                    current.entries[key]
                        .orEmpty()
                        .filterNot { it.mutationId == mutationId }
                val updatedEntries =
                    if (remaining.isEmpty()) {
                        current.entries - key
                    } else {
                        current.entries + (key to remaining)
                    }
                current.copy(entries = updatedEntries)
            }
        }
    }

    override suspend fun rehome(
        from: KeyIdentity,
        to: KeyIdentity,
        entry: JournalEntry<V>,
    ) {
        runtimeState.mutex.withLock {
            runtimeState.snapshots.update { current ->
                val sourceRemaining =
                    current.entries[from].orEmpty().filterNot { candidate ->
                        candidate.mutationId == entry.mutationId
                    }
                val withoutSource =
                    if (sourceRemaining.isEmpty()) {
                        current.entries - from
                    } else {
                        current.entries + (from to sourceRemaining)
                    }
                current.copy(
                    entries = withoutSource + (to to withoutSource[to].orEmpty() + entry),
                )
            }
        }
    }

    override fun pendingSnapshot(key: KeyIdentity): List<JournalEntry<V>> =
        runtimeState.snapshots.value.entries[key].orEmpty()

    override fun identities(): Set<KeyIdentity> = runtimeState.snapshots.value.entries.keys

    /** One immutable cache capture for inspection paths that must not split alias and entry reads. */
    internal fun runtimeSnapshot(): MutationRuntimeSnapshot<V> = runtimeState.snapshots.value

    /** Reads all nine record groups in one storage transaction; no consumer code runs inside. */
    internal suspend fun readDurableSnapshot(): DurableJournalSnapshot =
        storage.transaction { transaction ->
            DurableJournalSnapshot(
                client = transaction.client(clientId),
                intents = transaction.intents(clientId),
                executions = transaction.executions(clientId),
                attempts = transaction.attempts(clientId),
                acks = transaction.acks(clientId),
                failures = transaction.failures(clientId),
                effects = transaction.effects(clientId),
                aliases = transaction.aliases(),
                tombstones = transaction.tombstones(),
            )
        }

    /** Atomically replaces projection membership and alias routing after successful hydration. */
    internal suspend fun installHydratedState(
        hydrated: Map<KeyIdentity, List<JournalEntry<V>>>,
        aliases: Map<KeyIdentity, AliasEdge>,
    ) {
        runtimeState.mutex.withLock {
            runtimeState.snapshots.value =
                MutationRuntimeSnapshot(
                    entries = hydrated.mapValues { (_, rows) -> rows.toList() },
                    aliases = aliases.toMap(),
                )
        }
    }

    /**
     * Publishes one post-commit alias retirement as a single runtime-cache linearization point.
     * The durable edge remains the raw source-to-acknowledged-target fact; queued entries move to
     * the already-resolved terminal execution residence.
     */
    internal suspend fun publishAliasRetirement(
        source: KeyIdentity,
        terminalTarget: KeyIdentity,
        retiredMutationId: String,
    ) {
        runtimeState.mutex.withLock {
            runtimeState.snapshots.update { current ->
                val edge = checkNotNull(current.aliases[source]) {
                    "Alias retirement requires a published pending edge for $source."
                }
                val sourceSiblings =
                    current.entries[source]
                        .orEmpty()
                        .filterNot { entry -> entry.mutationId == retiredMutationId }
                val mergedTarget =
                    (current.entries[terminalTarget].orEmpty() + sourceSiblings)
                        .distinctBy { entry -> entry.mutationId }
                        .sortedBy { entry -> entry.durableClientSequence }
                var updatedEntries = current.entries - source
                updatedEntries =
                    if (mergedTarget.isEmpty()) {
                        updatedEntries - terminalTarget
                    } else {
                        updatedEntries + (terminalTarget to mergedTarget)
                    }
                current.copy(
                    entries = updatedEntries,
                    aliases =
                        current.aliases +
                            (source to edge.copy(state = AliasEdgeState.ACTIVE)),
                )
            }
        }
    }
}

/** The 021-compatible default journal, now implemented by the public in-memory storage seam. */
internal class InMemoryMutationJournal<V : Any> :
    StorageBackedMutationJournal<V>(storage = InMemoryMutationJournalStorage())

// ---------------------------------------------------------------------------------------------
// Cache-fronted canonical alias routing (D15a).
// ---------------------------------------------------------------------------------------------

/** Stable machine detail for a canonical target in a different namespace (D15a). */
internal const val ALIAS_FAILURE_DETAIL_CROSS_NAMESPACE: String = "alias-cross-namespace"

/** Stable machine detail for a second canonical target claimed for an aliased source (D15a). */
internal const val ALIAS_FAILURE_DETAIL_RETARGET: String = "alias-retarget"

/** Stable machine detail for a canonical target whose chain reaches back to its source (D15a). */
internal const val ALIAS_FAILURE_DETAIL_CYCLE: String = "alias-cycle"

/** Stable machine detail for a generation retry acknowledging a different canonical target (D15a). */
internal const val ALIAS_FAILURE_DETAIL_RETRY_TARGET_MISMATCH: String =
    "alias-retry-target-mismatch"

/**
 * The lifecycle of one alias edge (D15a): `PENDING` between validated acknowledgement receipt and
 * retirement, `ACTIVE` from the retirement/activation step onward. Routing follows only `ACTIVE`
 * edges; a `PENDING` edge already pins its source against retarget and its idempotency receipt
 * against a mismatched retry.
 */
internal enum class AliasEdgeState {
    PENDING,
    ACTIVE,
}

/** One normalized same-namespace full-pair redirect: source identity to target identity (D15a). */
internal data class AliasEdge(
    val source: KeyIdentity,
    val target: KeyIdentity,
    val state: AliasEdgeState,
    val createdByClientId: String,
    val createdBySequence: Long,
    val createdAt: Long,
)

private fun MutationKeyAliasRecord.toAliasEdge(): AliasEdge =
    AliasEdge(
        source = KeyIdentity(sourceNamespace, sourceCanonicalId),
        target = KeyIdentity(targetNamespace, targetCanonicalId),
        state =
            when (state) {
                MutationAliasState.PENDING -> AliasEdgeState.PENDING
                MutationAliasState.ACTIVE -> AliasEdgeState.ACTIVE
            },
        createdByClientId = createdByClientId,
        createdBySequence = createdBySequence,
        createdAt = createdAt,
    )

internal fun List<MutationKeyAliasRecord>.toAliasEdgesBySource(): Map<KeyIdentity, AliasEdge> =
    associate { record ->
        val edge = record.toAliasEdge()
        edge.source to edge
    }

internal fun terminalIdentity(
    identity: KeyIdentity,
    edges: Map<KeyIdentity, AliasEdge>,
): KeyIdentity {
    var current = identity
    val visited = mutableSetOf<KeyIdentity>()
    while (true) {
        val edge = edges[current] ?: return current
        if (edge.state != AliasEdgeState.ACTIVE) return current
        check(visited.add(current)) {
            "Alias chain from (${identity.namespace}, ${identity.canonicalId}) cycled at " +
                "(${current.namespace}, ${current.canonicalId})."
        }
        current = edge.target
    }
}

/** The outcome of validating one acknowledged canonical target at ack receipt (D15a). */
internal sealed interface AliasAdmission {
    /**
     * The acknowledgement is legal. [redirect] is the pending-or-existing edge for a real
     * same-namespace redirect, or `null` when the identity is unchanged (null canonical key or
     * self-alias — both no-ops by rule).
     */
    class Admitted(
        val redirect: AliasEdge?,
        internal val pendingRecord: MutationKeyAliasRecord?,
        internal val idempotencyKey: String,
        internal val effectiveTarget: KeyIdentity,
    ) : AliasAdmission

    /** The acknowledgement violates the alias protocol; the intent halts without adoption. */
    class Rejected(
        val detail: String,
        val message: String,
    ) : AliasAdmission
}

/**
 * Cache-fronted alias adapter: normalized same-namespace full-pair redirects with
 * `PENDING`/`ACTIVE` states, transitive terminal resolution, and generation-idempotency receipt
 * tracking (D15a normative rules; D14 facade routing).
 *
 * PENDING and ACTIVE changes commit through [storage] before this synchronous routing cache is
 * published. Restart hydration restores both states without advancing an advisory revision.
 * Tombstone storage/hydration is modeled separately; tombstone activation orchestration and
 * transactional retirement composition remain later slices. Deferred proofs: 022
 * `MutationJournalContractTest.kt::aliasEdgesAndActivation_roundTripAcrossRestart`; 023
 * `MutationAckOrchestrationTest.kt::ackAliasActivationRebasesQueuedSourceAndTargetSiblings`.
 * Tombstone generations and high-water interaction are R1-21's 022/023/024 tests; 021 records
 * no tombstones.
 */
internal class InMemoryAliasRouter(
    private val storage: MutationJournalStorage = InMemoryMutationJournalStorage(),
    private val runtimeState: MutationRuntimeState<*> = MutationRuntimeState<Any>(),
) {
    // D15a: a retry of one generation idempotency key must return the same canonical target.
    // The receipt records the EFFECTIVE canonical identity (the source itself when the ack
    // carried no target or a self-alias), so a retry that flips between "unchanged" and a real
    // redirect is also a protocol failure. 022 owns the durable receipt row.
    private val effectiveTargetsByIdempotencyKey = mutableMapOf<String, KeyIdentity>()

    /** Clears process-only retry receipts after the shared durable snapshot is installed. */
    fun resetAfterHydration() {
        effectiveTargetsByIdempotencyKey.clear()
    }

    /**
     * Resolves the terminal identity for [identity] by following `ACTIVE` edges transitively
     * (D15a: chains resolve transitively). Cycles are unrepresentable by construction — [admit]
     * rejects them — so the walk guard is defensive only. Callers may cache the resolved
     * terminal `K`, but durable edges remain the authority (path compression never replaces
     * them).
     */
    fun terminalOf(identity: KeyIdentity): KeyIdentity {
        return terminalIdentity(identity, runtimeState.aliasesSnapshot())
    }

    /**
     * Validates one acknowledged canonical target at ack receipt and prepares, but does not
     * persist or publish, an optional pending redirect edge (D15a):
     * cross-namespace targets are rejected; a receipt mismatch for [idempotencyKey] is rejected;
     * a null or self target is an admitted no-op; a duplicate equal edge is idempotent; a
     * different target for an already-aliased source is a retarget rejection; a target whose
     * active-or-pending chain reaches back to [source] is a cycle rejection.
     */
    suspend fun admit(
        source: KeyIdentity,
        claimed: KeyIdentity?,
        idempotencyKey: String,
        createdByClientId: String,
        createdBySequence: Long,
        createdAt: Long,
    ): AliasAdmission {
        if (claimed != null && claimed.namespace != source.namespace) {
            return AliasAdmission.Rejected(
                detail = ALIAS_FAILURE_DETAIL_CROSS_NAMESPACE,
                message =
                    "Canonical key (${claimed.namespace}, ${claimed.canonicalId}) crosses the " +
                        "namespace of (${source.namespace}, ${source.canonicalId}); " +
                        "cross-namespace rekey is out of the alpha01 contract.",
            )
        }
        val effectiveTarget = claimed ?: source
        val receipt = effectiveTargetsByIdempotencyKey[idempotencyKey]
        if (receipt != null && receipt != effectiveTarget) {
            return AliasAdmission.Rejected(
                detail = ALIAS_FAILURE_DETAIL_RETRY_TARGET_MISMATCH,
                message =
                    "Retry of idempotency key '$idempotencyKey' acknowledged canonical target " +
                        "(${effectiveTarget.namespace}, ${effectiveTarget.canonicalId}) but " +
                        "(${receipt.namespace}, ${receipt.canonicalId}) was previously " +
                        "acknowledged.",
            )
        }
        if (claimed == null || claimed == source) {
            return AliasAdmission.Admitted(
                redirect = null,
                pendingRecord = null,
                idempotencyKey = idempotencyKey,
                effectiveTarget = effectiveTarget,
            )
        }
        val edges = runtimeState.aliasesSnapshot()
        val existing = edges[source]
        if (existing != null && existing.target != claimed) {
            return AliasAdmission.Rejected(
                detail = ALIAS_FAILURE_DETAIL_RETARGET,
                message =
                    "Source (${source.namespace}, ${source.canonicalId}) already redirects to " +
                        "(${existing.target.namespace}, ${existing.target.canonicalId}); " +
                        "retargeting to (${claimed.namespace}, ${claimed.canonicalId}) is a " +
                        "protocol violation.",
            )
        }
        if (existing == null && reaches(edges = edges, from = claimed, needle = source)) {
            return AliasAdmission.Rejected(
                detail = ALIAS_FAILURE_DETAIL_CYCLE,
                message =
                    "Aliasing (${source.namespace}, ${source.canonicalId}) to " +
                        "(${claimed.namespace}, ${claimed.canonicalId}) would close an alias " +
                        "cycle.",
            )
        }
        val pendingRecord =
            if (existing == null) {
                MutationKeyAliasRecord(
                    sourceNamespace = source.namespace,
                    sourceCanonicalId = source.canonicalId,
                    targetNamespace = claimed.namespace,
                    targetCanonicalId = claimed.canonicalId,
                    state = MutationAliasState.PENDING,
                    createdByClientId = createdByClientId,
                    createdBySequence = createdBySequence,
                    createdAt = createdAt,
                    activatedAt = null,
                )
            } else {
                null
            }
        val edge =
            existing
                ?: AliasEdge(
                    source = source,
                    target = claimed,
                    state = AliasEdgeState.PENDING,
                    createdByClientId = createdByClientId,
                    createdBySequence = createdBySequence,
                    createdAt = createdAt,
                )
        return AliasAdmission.Admitted(
            redirect = edge,
            pendingRecord = pendingRecord,
            idempotencyKey = idempotencyKey,
            effectiveTarget = effectiveTarget,
        )
    }

    /** Commits a non-durable-engine admission, then publishes its routing cache state. */
    suspend fun commitAdmission(admission: AliasAdmission.Admitted) {
        admission.pendingRecord?.let { record ->
            storage.transaction { transaction -> transaction.insertAlias(record) }
        }
        publishAdmission(admission)
    }

    /** Publishes an admission only after the caller's durable transaction has committed. */
    fun publishAdmission(admission: AliasAdmission.Admitted) {
        runtimeState.updateAliases { edges ->
            admission.pendingRecord?.let { record ->
                edges +
                    (KeyIdentity(record.sourceNamespace, record.sourceCanonicalId) to
                        checkNotNull(admission.redirect))
            } ?: edges
        }
        effectiveTargetsByIdempotencyKey[admission.idempotencyKey] = admission.effectiveTarget
    }

    /**
     * Activates the redirect for [source] (D15a step 5's in-memory analog). Activation is
     * idempotent; the caller performs it inside the `NonCancellable` retirement handoff and then
     * synchronously advances the mutation-owned alias revision.
     */
    suspend fun activate(
        source: KeyIdentity,
        activatedAt: Long,
    ) {
        val record = activationRecord(source, activatedAt) ?: return
        storage.transaction { transaction ->
            transaction.advanceAlias(record)
        }
        publishActivation(source)
    }

    /** Persists a prepared ACTIVE row without publishing a separate routing-cache state. */
    suspend fun persistActivation(record: MutationKeyAliasRecord) {
        storage.transaction { transaction -> transaction.advanceAlias(record) }
    }

    /** Builds the immutable ACTIVE row without touching storage or the routing cache. */
    fun activationRecord(
        source: KeyIdentity,
        activatedAt: Long,
    ): MutationKeyAliasRecord? {
        val edge = runtimeState.aliasesSnapshot()[source] ?: return null
        if (edge.state == AliasEdgeState.ACTIVE) return null
        return MutationKeyAliasRecord(
            sourceNamespace = edge.source.namespace,
            sourceCanonicalId = edge.source.canonicalId,
            targetNamespace = edge.target.namespace,
            targetCanonicalId = edge.target.canonicalId,
            state = MutationAliasState.ACTIVE,
            createdByClientId = edge.createdByClientId,
            createdBySequence = edge.createdBySequence,
            createdAt = edge.createdAt,
            activatedAt = activatedAt,
        )
    }

    /** Publishes a previously committed ACTIVE row to the synchronous routing cache. */
    fun publishActivation(source: KeyIdentity) {
        runtimeState.updateAliases { edges ->
            val edge = edges[source] ?: return@updateAliases edges
            edges + (source to edge.copy(state = AliasEdgeState.ACTIVE))
        }
    }

    /** The redirect edge whose source is [source], regardless of state; test/engine door. */
    fun edgeFor(source: KeyIdentity): AliasEdge? = runtimeState.aliasesSnapshot()[source]

    /** Cycle guard: walks pending-or-active edges from [from] looking for [needle]. */
    private fun reaches(
        edges: Map<KeyIdentity, AliasEdge>,
        from: KeyIdentity,
        needle: KeyIdentity,
    ): Boolean {
        var current = from
        val visited = mutableSetOf<KeyIdentity>()
        while (true) {
            if (current == needle) return true
            val edge = edges[current] ?: return false
            if (!visited.add(current)) return false
            current = edge.target
        }
    }
}
