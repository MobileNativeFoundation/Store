@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.storage

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.mutations.MutationFailureKind
import org.mobilenativefoundation.store6.mutations.MutationPresenceState

/**
 * Stable persisted execution-phase names. Storage implementations persist `name`, never ordinal.
 *
 * [PARKED] and [RETIRED] are the two terminal phases: a parked execution carries an active failure
 * id and appears only in dead letters, and a retired one carries its retirement time and appears in
 * neither inspection API. The six nonterminal phases are the durable form of the public
 * [org.mobilenativefoundation.store6.mutations.MutationPendingState] vocabulary, which documents
 * the mapping.
 */
@ExperimentalStoreApi
public enum class MutationExecutionPhase {
    UNPREPARED,
    READY,
    INFLIGHT,
    REFRESH_REQUIRED,
    ACKED,
    EFFECTS_PENDING,
    PARKED,
    RETIRED,
}

/** Stable persisted invalidation-effect target names. */
@ExperimentalStoreApi
public enum class MutationEffectKind {
    KEY,
    NAMESPACE,
}

/** Stable persisted invalidation-effect disposition names. */
@ExperimentalStoreApi
public enum class MutationEffectDisposition {
    PENDING,
    APPLIED,
    SKIPPED,
}

/** Stable persisted alias-edge state names. */
@ExperimentalStoreApi
public enum class MutationAliasState {
    PENDING,
    ACTIVE,
}

/** Stable persisted tombstone-generation state names. */
@ExperimentalStoreApi
public enum class MutationTombstoneState {
    PENDING,
    ACTIVE,
    SUPERSEDED,
}

/** Durable allocation and retirement high-water state for one mutation client. */
@ExperimentalStoreApi
public class MutationClientRecord(
    @ExperimentalStoreApi public val recordVersion: Int,
    @ExperimentalStoreApi public val clientId: String,
    @ExperimentalStoreApi public val lastAllocatedSequence: Long,
    @ExperimentalStoreApi public val retiredThroughSequence: Long,
    @ExperimentalStoreApi public val serverConfirmedRetiredThroughSequence: Long,
    @ExperimentalStoreApi public val createdAt: Long,
) {
    init {
        require(recordVersion > 0) { "recordVersion must be positive" }
        require(serverConfirmedRetiredThroughSequence >= 0L) {
            "server-confirmed retirement prefix must be non-negative"
        }
        require(serverConfirmedRetiredThroughSequence <= retiredThroughSequence) {
            "server-confirmed retirement prefix cannot exceed the local prefix"
        }
        require(retiredThroughSequence <= lastAllocatedSequence) {
            "local retirement prefix cannot exceed the allocated sequence"
        }
    }
}

/** Immutable durable intent. `argsBlob` is copied on construction and every read. */
@ExperimentalStoreApi
public class MutationIntentRecord(
    @ExperimentalStoreApi public val rowId: Long,
    @ExperimentalStoreApi public val recordVersion: Int,
    @ExperimentalStoreApi public val clientId: String,
    @ExperimentalStoreApi public val clientSequence: Long,
    @ExperimentalStoreApi public val mutationId: String,
    @ExperimentalStoreApi public val namespace: String,
    @ExperimentalStoreApi public val canonicalId: String,
    @ExperimentalStoreApi public val mutatorId: String,
    @ExperimentalStoreApi public val mutatorVersion: Int,
    argsBlob: ByteArray,
    @ExperimentalStoreApi public val idempotencyRoot: String,
    @ExperimentalStoreApi public val createdAt: Long,
) {
    private val argsBlobValue: ByteArray = argsBlob.copyOf()

    /** Defensively copied encoded mutator arguments. */
    @ExperimentalStoreApi
    public val argsBlob: ByteArray
        get() = argsBlobValue.copyOf()

    init {
        require(recordVersion > 0) { "recordVersion must be positive" }
        require(clientSequence > 0L) { "clientSequence must be positive" }
        require(mutatorVersion > 0) { "mutatorVersion must be positive" }
    }
}

/** Mutable durable execution state for exactly one [MutationIntentRecord]. */
@ExperimentalStoreApi
public class MutationExecutionRecord(
    @ExperimentalStoreApi public val clientId: String,

    /** The owning intent's durable per-client sequence; always positive. */
    @ExperimentalStoreApi public val clientSequence: Long,
    @ExperimentalStoreApi public val phase: MutationExecutionPhase,

    /**
     * The semantic generation this execution references; never negative. It is zero only in
     * [MutationExecutionPhase.UNPREPARED] and in a [MutationExecutionPhase.PARKED] execution that
     * never prepared one; every other phase references a prepared generation.
     */
    @ExperimentalStoreApi public val currentGeneration: Int,

    /**
     * Completed network attempts for `currentGeneration`; never negative, and zero while
     * `currentGeneration` is zero.
     */
    @ExperimentalStoreApi public val attempt: Int,

    /**
     * When the latest network attempt completed, in Unix epoch milliseconds. Non-null exactly
     * when `attempt` is nonzero.
     */
    @ExperimentalStoreApi public val lastAttemptAt: Long?,

    /**
     * The [MutationFailureRecord.failureId] retained by a parked execution. Non-null exactly in
     * [MutationExecutionPhase.PARKED].
     */
    @ExperimentalStoreApi public val activeFailureId: Long?,

    /**
     * When this execution retired, in Unix epoch milliseconds. Non-null exactly in
     * [MutationExecutionPhase.RETIRED].
     */
    @ExperimentalStoreApi public val retiredAt: Long?,
) {
    init {
        require(clientSequence > 0L) { "clientSequence must be positive" }
        require(currentGeneration >= 0) { "currentGeneration must be non-negative" }
        require(attempt >= 0) { "attempt must be non-negative" }
        require((attempt == 0) == (lastAttemptAt == null)) {
            "lastAttemptAt must exist exactly when a network attempt has completed"
        }
        if (currentGeneration == 0) {
            require(attempt == 0 && lastAttemptAt == null) {
                "generation zero cannot contain completed network-attempt facts"
            }
        }
        require((phase == MutationExecutionPhase.PARKED) == (activeFailureId != null)) {
            "activeFailureId must exist exactly in PARKED"
        }
        require((phase == MutationExecutionPhase.RETIRED) == (retiredAt != null)) {
            "retiredAt must exist exactly in RETIRED"
        }
        if (phase == MutationExecutionPhase.UNPREPARED) {
            require(currentGeneration == 0) { "UNPREPARED cannot reference a generation" }
        } else if (phase != MutationExecutionPhase.PARKED || currentGeneration != 0) {
            require(currentGeneration > 0) { "$phase must reference a prepared generation" }
        }
    }
}

/** Immutable semantic attempt generation with a write-once conflict receipt. */
@ExperimentalStoreApi
public class MutationAttemptRecord(
    @ExperimentalStoreApi public val clientId: String,
    @ExperimentalStoreApi public val clientSequence: Long,
    @ExperimentalStoreApi public val generation: Int,
    @ExperimentalStoreApi public val effectiveNamespace: String,
    @ExperimentalStoreApi public val effectiveCanonicalId: String,
    @ExperimentalStoreApi public val valueCodecVersion: Int,
    @ExperimentalStoreApi public val basePresence: MutationPresenceState,
    baseBlob: ByteArray?,
    @ExperimentalStoreApi public val minePresence: MutationPresenceState,
    mineBlob: ByteArray?,
    @ExperimentalStoreApi public val preconditionMetaPresent: Boolean,
    @ExperimentalStoreApi public val preconditionWrittenAt: Long?,
    @ExperimentalStoreApi public val preconditionEtag: String?,
    @ExperimentalStoreApi public val advertisedRetiredThroughSequence: Long,
    @ExperimentalStoreApi public val generationIdempotencyKey: String,
    @ExperimentalStoreApi public val preparedAt: Long,
    @ExperimentalStoreApi public val conflictMetaPresent: Boolean?,
    @ExperimentalStoreApi public val conflictWrittenAt: Long?,
    @ExperimentalStoreApi public val conflictEtag: String?,
    @ExperimentalStoreApi public val conflictReceivedAt: Long?,
) {
    private val baseBlobValue: ByteArray? = baseBlob?.copyOf()
    private val mineBlobValue: ByteArray? = mineBlob?.copyOf()

    /** Defensively copied encoded base value, or null for an absent base. */
    @ExperimentalStoreApi
    public val baseBlob: ByteArray?
        get() = baseBlobValue?.copyOf()

    /** Defensively copied encoded optimistic value, or null for an absent value. */
    @ExperimentalStoreApi
    public val mineBlob: ByteArray?
        get() = mineBlobValue?.copyOf()

    init {
        require(clientSequence > 0L) { "clientSequence must be positive" }
        require(generation > 0) { "generation must be positive" }
        require(valueCodecVersion > 0) { "valueCodecVersion must be positive" }
        require(advertisedRetiredThroughSequence >= 0L) {
            "advertisedRetiredThroughSequence must be non-negative"
        }
        require((basePresence == MutationPresenceState.PRESENT) == (baseBlobValue != null)) {
            "baseBlob presence must match basePresence"
        }
        require((minePresence == MutationPresenceState.PRESENT) == (mineBlobValue != null)) {
            "mineBlob presence must match minePresence"
        }
        require(preconditionMetaPresent == (preconditionWrittenAt != null)) {
            "preconditionWrittenAt presence must match preconditionMetaPresent"
        }
        if (!preconditionMetaPresent) {
            require(preconditionEtag == null) { "preconditionEtag requires metadata" }
        }
        if (conflictMetaPresent == null) {
            require(conflictWrittenAt == null && conflictEtag == null && conflictReceivedAt == null) {
                "an absent conflict receipt cannot carry receipt fields"
            }
        } else {
            require(conflictReceivedAt != null) { "a conflict receipt requires conflictReceivedAt" }
            require(conflictMetaPresent == (conflictWrittenAt != null)) {
                "conflictWrittenAt presence must match conflictMetaPresent"
            }
            if (!conflictMetaPresent) {
                require(conflictEtag == null) { "conflictEtag requires conflict metadata" }
            }
        }
    }
}

/** Write-once durable acknowledgement for an exact attempt generation. */
@ExperimentalStoreApi
public class MutationAckRecord(
    @ExperimentalStoreApi public val clientId: String,
    @ExperimentalStoreApi public val clientSequence: Long,
    @ExperimentalStoreApi public val generation: Int,
    @ExperimentalStoreApi public val authoritativePresence: MutationPresenceState,
    authoritativeBlob: ByteArray?,
    @ExperimentalStoreApi public val valueCodecVersion: Int,
    @ExperimentalStoreApi public val etag: String?,
    @ExperimentalStoreApi public val canonicalTargetNamespace: String?,
    @ExperimentalStoreApi public val canonicalTargetId: String?,
    @ExperimentalStoreApi public val receivedAt: Long,
) {
    private val authoritativeBlobValue: ByteArray? = authoritativeBlob?.copyOf()

    /** Defensively copied server-authoritative value, or null for authoritative absence. */
    @ExperimentalStoreApi
    public val authoritativeBlob: ByteArray?
        get() = authoritativeBlobValue?.copyOf()

    init {
        require(clientSequence > 0L) { "clientSequence must be positive" }
        require(generation > 0) { "generation must be positive" }
        require(valueCodecVersion > 0) { "valueCodecVersion must be positive" }
        require(
            (authoritativePresence == MutationPresenceState.PRESENT) ==
                (authoritativeBlobValue != null),
        ) { "authoritativeBlob presence must match authoritativePresence" }
        require((canonicalTargetNamespace == null) == (canonicalTargetId == null)) {
            "canonical target fields must be both null or both present"
        }
        if (canonicalTargetNamespace != null) {
            require(authoritativePresence == MutationPresenceState.PRESENT) {
                "canonical targets require an authoritative present value"
            }
        }
    }
}

/** Append-only normalized failure evidence. */
@ExperimentalStoreApi
public class MutationFailureRecord(
    @ExperimentalStoreApi public val failureId: Long,
    @ExperimentalStoreApi public val clientId: String,
    @ExperimentalStoreApi public val clientSequence: Long,
    @ExperimentalStoreApi public val generation: Int,
    @ExperimentalStoreApi public val kind: MutationFailureKind,
    @ExperimentalStoreApi public val detail: String,
    @ExperimentalStoreApi public val message: String,
    @ExperimentalStoreApi public val occurredAt: Long,
) {
    init {
        require(clientSequence > 0L) { "clientSequence must be positive" }
        require(generation >= 0) { "generation must be non-negative" }
        require(detail.encodeToByteArray().size <= FAILURE_DETAIL_MAX_UTF8_BYTES) {
            "detail exceeds $FAILURE_DETAIL_MAX_UTF8_BYTES UTF-8 bytes"
        }
        require(message.encodeToByteArray().size <= FAILURE_MESSAGE_MAX_UTF8_BYTES) {
            "message exceeds $FAILURE_MESSAGE_MAX_UTF8_BYTES UTF-8 bytes"
        }
    }
}

/** One normalized durable invalidation effect. */
@ExperimentalStoreApi
public class MutationEffectRecord(
    @ExperimentalStoreApi public val clientId: String,
    @ExperimentalStoreApi public val clientSequence: Long,
    @ExperimentalStoreApi public val effectIndex: Int,
    @ExperimentalStoreApi public val kind: MutationEffectKind,
    @ExperimentalStoreApi public val namespace: String,
    @ExperimentalStoreApi public val canonicalId: String?,
    @ExperimentalStoreApi public val createdAt: Long,
    @ExperimentalStoreApi public val disposition: MutationEffectDisposition,
    @ExperimentalStoreApi public val completedAt: Long?,
) {
    init {
        require(clientSequence > 0L) { "clientSequence must be positive" }
        require(effectIndex >= 0) { "effectIndex must be non-negative" }
        require((kind == MutationEffectKind.KEY) == (canonicalId != null)) {
            "canonicalId must exist exactly for KEY effects"
        }
        require((disposition == MutationEffectDisposition.PENDING) == (completedAt == null)) {
            "completedAt must be null exactly while an effect is PENDING"
        }
    }
}

/** Durable same-namespace redirect edge. */
@ExperimentalStoreApi
public class MutationKeyAliasRecord(
    @ExperimentalStoreApi public val sourceNamespace: String,
    @ExperimentalStoreApi public val sourceCanonicalId: String,
    @ExperimentalStoreApi public val targetNamespace: String,
    @ExperimentalStoreApi public val targetCanonicalId: String,
    @ExperimentalStoreApi public val state: MutationAliasState,
    @ExperimentalStoreApi public val createdByClientId: String,
    @ExperimentalStoreApi public val createdBySequence: Long,
    @ExperimentalStoreApi public val createdAt: Long,
    @ExperimentalStoreApi public val activatedAt: Long?,
) {
    init {
        require(createdBySequence > 0L) { "createdBySequence must be positive" }
        require(sourceNamespace == targetNamespace) { "alias edges cannot cross namespaces" }
        require(sourceCanonicalId != targetCanonicalId) { "self alias edges are not stored" }
        require((state == MutationAliasState.ACTIVE) == (activatedAt != null)) {
            "activatedAt must exist exactly for ACTIVE aliases"
        }
    }
}

/** One durable tombstone generation for an effective identity. */
@ExperimentalStoreApi
public class MutationKeyTombstoneRecord(
    @ExperimentalStoreApi public val namespace: String,
    @ExperimentalStoreApi public val canonicalId: String,
    @ExperimentalStoreApi public val createdByClientId: String,
    @ExperimentalStoreApi public val createdBySequence: Long,
    @ExperimentalStoreApi public val state: MutationTombstoneState,
    @ExperimentalStoreApi public val createdAt: Long,
    @ExperimentalStoreApi public val activatedAt: Long?,
    @ExperimentalStoreApi public val supersededByClientId: String?,
    @ExperimentalStoreApi public val supersededBySequence: Long?,
    @ExperimentalStoreApi public val supersededAt: Long?,
) {
    init {
        require(createdBySequence > 0L) { "createdBySequence must be positive" }
        require((supersededByClientId == null) == (supersededBySequence == null)) {
            "superseding identity fields must be both null or both present"
        }
        if (supersededBySequence != null) {
            require(supersededBySequence > 0L) {
                "supersededBySequence must identify a positive client sequence"
            }
        }
        when (state) {
            MutationTombstoneState.PENDING -> {
                require(activatedAt == null) { "PENDING tombstones are not activated" }
                require(supersededByClientId == null && supersededAt == null) {
                    "PENDING tombstones are not superseded"
                }
            }

            MutationTombstoneState.ACTIVE -> {
                require(activatedAt != null) { "ACTIVE tombstones require activatedAt" }
                require(supersededByClientId == null && supersededAt == null) {
                    "ACTIVE tombstones are not superseded"
                }
            }

            MutationTombstoneState.SUPERSEDED -> {
                require(activatedAt != null) { "SUPERSEDED tombstones retain activatedAt" }
                require(supersededByClientId != null && supersededAt != null) {
                    "SUPERSEDED tombstones require a complete successor and timestamp"
                }
                if (supersededByClientId == createdByClientId) {
                    require(supersededBySequence != createdBySequence) {
                        "a same-client superseding intent must be causally distinct"
                    }
                }
                // Same-client causal successors may carry a lower positive durable sequence. The
                // storage transaction validates that narrow exception against the complete
                // authority, acknowledgement, routing, effects, and retirement final state.
            }
        }
    }
}

internal const val FAILURE_DETAIL_MAX_UTF8_BYTES: Int = 128
internal const val FAILURE_MESSAGE_MAX_UTF8_BYTES: Int = 1_024
