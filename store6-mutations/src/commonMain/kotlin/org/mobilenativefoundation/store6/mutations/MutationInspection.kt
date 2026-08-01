@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/** The stable public presence names used by acknowledgement and adoption reporting. */
@ExperimentalStoreApi
public enum class MutationPresenceState {
    PRESENT,
    ABSENT,
}

/**
 * The total public mapping of every nonterminal active execution phase (D3).
 *
 * `UNPREPARED`/`READY` map to [PENDING], `INFLIGHT` to [INFLIGHT], `REFRESH_REQUIRED` to
 * [REFRESHING], `ACKED` to [ADOPTING], and `EFFECTS_PENDING` to [APPLYING_EFFECTS]. Parked
 * executions appear only in dead letters; retired executions appear in neither inspection API.
 */
@ExperimentalStoreApi
public enum class MutationPendingState {
    PENDING,
    INFLIGHT,
    REFRESHING,
    ADOPTING,
    APPLYING_EFFECTS,
}

/** The broad, append-only classification of a normalized mutation failure (R-0 §6). */
@ExperimentalStoreApi
public enum class MutationFailureKind {
    IDENTITY,
    CODEC,
    PROJECTION,
    PROTOCOL,
    CONFLICT,
    TRANSPORT,
    ADOPTION,
    EFFECT,
    PERSISTENCE,
}

/**
 * A normalized, restart-safe failure record (D3).
 *
 * A raw `StoreError` or `Throwable` is never persisted and never carried here. Sanitization
 * contract: [detail] is at most 128 UTF-8 bytes and [message] at most 1,024 UTF-8 bytes, each
 * truncated at a code-point boundary after control-character and stack-trace sanitization. The
 * library alone constructs failures.
 */
@ExperimentalStoreApi
public class MutationFailure internal constructor(
    /** The broad append-only failure classification. */
    @ExperimentalStoreApi
    public val kind: MutationFailureKind,

    /** Sanitized machine-readable detail; at most 128 UTF-8 bytes. */
    @ExperimentalStoreApi
    public val detail: String,

    /** Sanitized human-readable diagnostic; at most 1,024 UTF-8 bytes. */
    @ExperimentalStoreApi
    public val message: String,

    /** The failure occurrence time in Unix epoch milliseconds. */
    @ExperimentalStoreApi
    public val occurredAtEpochMillis: Long,
)

internal const val MUTATION_FAILURE_DETAIL_MAX_UTF8_BYTES: Int = 128
internal const val MUTATION_FAILURE_MESSAGE_MAX_UTF8_BYTES: Int = 1024

/**
 * The library-side [MutationFailure] factory enforcing the sanitization contract: stack-trace
 * lines are dropped (first line only), remaining ISO control characters are removed, and the
 * result is truncated to the UTF-8 byte budget at a code-point boundary.
 */
internal fun sanitizedMutationFailure(
    kind: MutationFailureKind,
    detail: String,
    message: String,
    occurredAtEpochMillis: Long,
): MutationFailure =
    MutationFailure(
        kind = kind,
        detail = sanitizeForFailure(detail, MUTATION_FAILURE_DETAIL_MAX_UTF8_BYTES),
        message = sanitizeForFailure(message, MUTATION_FAILURE_MESSAGE_MAX_UTF8_BYTES),
        occurredAtEpochMillis = occurredAtEpochMillis,
    )

private fun sanitizeForFailure(
    raw: String,
    maxUtf8Bytes: Int,
): String {
    val firstLine = raw.substringBefore('\n').substringBefore('\r')
    val withoutControls = firstLine.filterNot(Char::isISOControl)
    return truncateUtf8AtCodePointBoundary(withoutControls, maxUtf8Bytes)
}

private fun truncateUtf8AtCodePointBoundary(
    value: String,
    maxUtf8Bytes: Int,
): String {
    var usedBytes = 0
    var index = 0
    while (index < value.length) {
        val character = value[index]
        val isSurrogatePair =
            character.isHighSurrogate() &&
                index + 1 < value.length &&
                value[index + 1].isLowSurrogate()
        val codePointBytes =
            when {
                isSurrogatePair -> 4
                character.code < 0x80 -> 1
                character.code < 0x800 -> 2
                else -> 3
            }
        if (usedBytes + codePointBytes > maxUtf8Bytes) break
        usedBytes += codePointBytes
        index += if (isSurrogatePair) 2 else 1
    }
    return value.substring(0, index)
}

/**
 * A truthful snapshot of one nonterminal active intent (D3).
 *
 * Snapshots are durable-identity carriers: they expose the identity pair rather than a
 * reconstructed `K`, so inspection needs no resolver.
 */
@ExperimentalStoreApi
public class PendingIntent internal constructor(
    /** Verbatim effective namespace of the intent. */
    @ExperimentalStoreApi
    public val namespace: String,

    /** Verbatim effective canonical id of the intent. */
    @ExperimentalStoreApi
    public val canonicalId: String,

    /** The opaque public id assigned at enqueue. */
    @ExperimentalStoreApi
    public val mutationId: String,

    /** The registered mutator storage identity. */
    @ExperimentalStoreApi
    public val mutatorId: String,

    /** The public mapping of the current active execution phase. */
    @ExperimentalStoreApi
    public val state: MutationPendingState,

    /** Completed network attempts for the current generation. */
    @ExperimentalStoreApi
    public val attempt: Int,

    /** The durable enqueue time in Unix epoch milliseconds. */
    @ExperimentalStoreApi
    public val createdAtEpochMillis: Long,
)

/**
 * A durably parked intent (D3). Dead letters contain only parked entries; parking is legal only
 * before a successful acknowledgement is durably recorded, and a parked sequence never re-enters
 * the executable FIFO.
 */
@ExperimentalStoreApi
public class DeadLetter internal constructor(
    /** Verbatim effective namespace of the parked intent. */
    @ExperimentalStoreApi
    public val namespace: String,

    /** Verbatim effective canonical id of the parked intent. */
    @ExperimentalStoreApi
    public val canonicalId: String,

    /** The opaque public id assigned at enqueue. */
    @ExperimentalStoreApi
    public val mutationId: String,

    /** The registered mutator storage identity. */
    @ExperimentalStoreApi
    public val mutatorId: String,

    /** The generation current when the intent parked; 0 before first preparation. */
    @ExperimentalStoreApi
    public val generation: Int,

    /** Completed network attempts when the intent parked. */
    @ExperimentalStoreApi
    public val attempts: Int,

    /** The normalized terminal parked reason. */
    @ExperimentalStoreApi
    public val failure: MutationFailure,

    /** The park commit time in Unix epoch milliseconds. */
    @ExperimentalStoreApi
    public val parkedAtEpochMillis: Long,
)

/**
 * An ephemeral projection-failure report carrying the exact local `Throwable` (D3).
 *
 * The same throw parks the row with a normalized durable `PROJECTION` failure; the throwable
 * itself never crosses restart.
 */
@ExperimentalStoreApi
public class PoisonedIntent internal constructor(
    /** The opaque public id assigned at enqueue. */
    @ExperimentalStoreApi
    public val mutationId: String,

    /** The identifier of the registered projection that threw. */
    @ExperimentalStoreApi
    public val mutatorId: String,

    /** The exact failure thrown by the projection; in-process only, never durable. */
    @ExperimentalStoreApi
    public val failure: Throwable,
)
