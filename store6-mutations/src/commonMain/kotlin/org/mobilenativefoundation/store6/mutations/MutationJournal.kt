@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
    org.mobilenativefoundation.store6.core.DelicateStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mobilenativefoundation.store6.core.StoreKey

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
)

internal interface MutationJournal<V : Any> {
    suspend fun append(
        key: KeyIdentity,
        entry: JournalEntry<V>,
    ): String

    suspend fun retire(
        key: KeyIdentity,
        mutationId: String,
    )

    fun pendingSnapshot(key: KeyIdentity): List<JournalEntry<V>>

    /**
     * The durable identities that currently hold pending intents, in first-enqueue order (D12):
     * global drain enumerates these and reconstructs each `K` through the resolver; a live key
     * map is never this method's substitute.
     */
    fun identities(): Set<KeyIdentity>
}

internal class InMemoryMutationJournal<V : Any> : MutationJournal<V> {
    private val entries =
        MutableStateFlow<Map<KeyIdentity, List<JournalEntry<V>>>>(emptyMap())
    private val mutations = Mutex()

    override suspend fun append(
        key: KeyIdentity,
        entry: JournalEntry<V>,
    ): String =
        mutations.withLock {
            val current = entries.value
            entries.value = current + (key to current[key].orEmpty() + entry)
            entry.mutationId
        }

    override suspend fun retire(
        key: KeyIdentity,
        mutationId: String,
    ) {
        mutations.withLock {
            val current = entries.value
            val remaining =
                current[key]
                    .orEmpty()
                    .filterNot { it.mutationId == mutationId }
            entries.value =
                if (remaining.isEmpty()) {
                    current - key
                } else {
                    current + (key to remaining)
                }
        }
    }

    override fun pendingSnapshot(key: KeyIdentity): List<JournalEntry<V>> =
        entries.value[key].orEmpty()

    override fun identities(): Set<KeyIdentity> = entries.value.keys
}

// ---------------------------------------------------------------------------------------------
// In-memory canonical alias routing (D15a, 021 preview).
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
internal class AliasEdge(
    val source: KeyIdentity,
    val target: KeyIdentity,
    var state: AliasEdgeState,
)

/** The outcome of validating one acknowledged canonical target at ack receipt (D15a). */
internal sealed interface AliasAdmission {
    /**
     * The acknowledgement is legal. [redirect] is the pending-or-existing edge for a real
     * same-namespace redirect, or `null` when the identity is unchanged (null canonical key or
     * self-alias — both no-ops by rule).
     */
    class Admitted(
        val redirect: AliasEdge?,
    ) : AliasAdmission

    /** The acknowledgement violates the alias protocol; the intent halts without adoption. */
    class Rejected(
        val detail: String,
        val message: String,
    ) : AliasAdmission
}

/**
 * The 021 in-memory alias router: normalized same-namespace full-pair redirects with
 * `PENDING`/`ACTIVE` states, transitive terminal resolution, and generation-idempotency receipt
 * tracking (D15a normative rules; D14 facade routing).
 *
 * DOUBLE-BUILD NOTE (plan T4.6): this router is a same-process preview and is expected to be
 * substantially rewritten when Issues 022/023 build canonical routing over durable records —
 * durable edges, restart rehydration, tombstone generations, retired-prefix interaction, and
 * pruning immunity are all deferred. Its TESTS (`MutationAliasFacadeTest`), not this
 * implementation, are the durable asset; 022's executor must not treat this machinery as
 * protected. Deferred proofs: 022
 * `MutationJournalContractTest.kt::aliasEdgesAndActivation_roundTripAcrossRestart`; 023
 * `MutationAckOrchestrationTest.kt::ackAliasActivationRebasesQueuedSourceAndTargetSiblings`.
 * Tombstone generations and high-water interaction are R1-21's 022/023/024 tests; 021 records
 * no tombstones.
 */
internal class InMemoryAliasRouter {
    private val edges = mutableMapOf<KeyIdentity, AliasEdge>()

    // D15a: a retry of one generation idempotency key must return the same canonical target.
    // The receipt records the EFFECTIVE canonical identity (the source itself when the ack
    // carried no target or a self-alias), so a retry that flips between "unchanged" and a real
    // redirect is also a protocol failure. 022 owns the durable receipt row.
    private val effectiveTargetsByIdempotencyKey = mutableMapOf<String, KeyIdentity>()

    /**
     * Resolves the terminal identity for [identity] by following `ACTIVE` edges transitively
     * (D15a: chains resolve transitively). Cycles are unrepresentable by construction — [admit]
     * rejects them — so the walk guard is defensive only. Callers may cache the resolved
     * terminal `K`, but durable edges remain the authority (path compression never replaces
     * them).
     */
    fun terminalOf(identity: KeyIdentity): KeyIdentity {
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

    /**
     * Validates one acknowledged canonical target at ack receipt and, when legal, inserts or
     * reuses the pending redirect edge and records the generation-idempotency receipt (D15a):
     * cross-namespace targets are rejected; a receipt mismatch for [idempotencyKey] is rejected;
     * a null or self target is an admitted no-op; a duplicate equal edge is idempotent; a
     * different target for an already-aliased source is a retarget rejection; a target whose
     * active-or-pending chain reaches back to [source] is a cycle rejection.
     */
    fun admit(
        source: KeyIdentity,
        claimed: KeyIdentity?,
        idempotencyKey: String,
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
            effectiveTargetsByIdempotencyKey[idempotencyKey] = effectiveTarget
            return AliasAdmission.Admitted(redirect = null)
        }
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
        if (existing == null && reaches(from = claimed, needle = source)) {
            return AliasAdmission.Rejected(
                detail = ALIAS_FAILURE_DETAIL_CYCLE,
                message =
                    "Aliasing (${source.namespace}, ${source.canonicalId}) to " +
                        "(${claimed.namespace}, ${claimed.canonicalId}) would close an alias " +
                        "cycle.",
            )
        }
        val edge =
            existing ?: AliasEdge(
                source = source,
                target = claimed,
                state = AliasEdgeState.PENDING,
            ).also { edges[source] = it }
        effectiveTargetsByIdempotencyKey[idempotencyKey] = claimed
        return AliasAdmission.Admitted(redirect = edge)
    }

    /**
     * Activates the redirect for [source] (D15a step 5's in-memory analog). Activation is
     * idempotent; the caller performs it inside the `NonCancellable` retirement handoff and then
     * synchronously advances the mutation-owned alias revision.
     */
    fun activate(source: KeyIdentity) {
        edges[source]?.state = AliasEdgeState.ACTIVE
    }

    /** The redirect edge whose source is [source], regardless of state; test/engine door. */
    fun edgeFor(source: KeyIdentity): AliasEdge? = edges[source]

    /** Cycle guard: walks pending-or-active edges from [from] looking for [needle]. */
    private fun reaches(
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
