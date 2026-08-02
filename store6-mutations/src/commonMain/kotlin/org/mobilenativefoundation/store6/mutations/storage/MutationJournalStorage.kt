package org.mobilenativefoundation.store6.mutations.storage

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.mutations.MutationFailureKind

/**
 * Durable mutation-journal storage seam.
 *
 * R-0 freezes the nine logical records represented in this package. Implementations persist enum
 * names, never ordinals; store every time as Unix epoch milliseconds; copy every byte array on
 * entry and delivery; and preserve the transition, uniqueness, ordering, and pruning rules
 * enforced by the testing contract kit.
 *
 * [transaction] is the single generic unit-of-work door. Normal return commits every operation;
 * any thrown [Throwable], including cancellation, commits none and propagates unchanged. The
 * callback is deliberately non-suspending so codec, resolver, transport, and policy work cannot
 * suspend while a journal transaction is held. A transaction handle is invalid after the callback
 * returns.
 *
 * A committed change that alters projection membership or routing is only the durable half of the
 * mutation accepted-state handoff. The engine synchronously publishes the corresponding overlay or
 * alias revision after commit and before external completion or cancellation becomes observable.
 */
@ExperimentalStoreApi
@SubclassOptInRequired(DelicateStoreApi::class)
public interface MutationJournalStorage {
    /** Runs [block] as one serializable, exception-atomic unit of work. */
    public suspend fun <R> transaction(block: (MutationJournalTransaction) -> R): R
}

/** Scoped reads and mutations available only inside [MutationJournalStorage.transaction]. */
@ExperimentalStoreApi
@SubclassOptInRequired(DelicateStoreApi::class)
public interface MutationJournalTransaction {
    /** Returns the client row, or null when this journal has not initialized [clientId]. */
    public fun client(clientId: String): MutationClientRecord?

    /** Returns retained intents ordered by client sequence. */
    public fun intents(clientId: String): List<MutationIntentRecord>

    /** Returns retained executions ordered by client sequence. */
    public fun executions(clientId: String): List<MutationExecutionRecord>

    /** Returns retained attempts ordered by client sequence then generation. */
    public fun attempts(clientId: String): List<MutationAttemptRecord>

    /** Returns retained acknowledgements ordered by client sequence then generation. */
    public fun acks(clientId: String): List<MutationAckRecord>

    /** Returns append-only failures ordered by storage-local failure ID. */
    public fun failures(clientId: String): List<MutationFailureRecord>

    /** Returns effects ordered by client sequence then effect index. */
    public fun effects(clientId: String): List<MutationEffectRecord>

    /** Returns every retained alias edge ordered by source identity. */
    public fun aliases(): List<MutationKeyAliasRecord>

    /** Returns every retained tombstone generation in deterministic identity order. */
    public fun tombstones(): List<MutationKeyTombstoneRecord>

    /**
     * Inserts a new version-1 client row with allocation and both retirement prefixes equal to
     * zero. Later hydrated states are represented only by monotonic advances of that initial row.
     */
    public fun insertClient(record: MutationClientRecord)

    /**
     * Monotonically advances allocation and local retirement fields. The persisted server-confirmed
     * field must remain unchanged; only [confirmRetiredThrough] may advance it.
     */
    public fun advanceClient(record: MutationClientRecord)

    /**
     * Monotonically records a checkpoint response without accepting a value greater than the
     * exact [requestedThroughSequence] sent to the backend.
     */
    public fun confirmRetiredThrough(
        clientId: String,
        requestedThroughSequence: Long,
        serverConfirmedThroughSequence: Long,
    ): MutationClientRecord

    /** Allocates a storage-local row ID, inserts an immutable intent, and returns its copied row. */
    public fun insertIntent(
        recordVersion: Int,
        clientId: String,
        clientSequence: Long,
        mutationId: String,
        namespace: String,
        canonicalId: String,
        mutatorId: String,
        mutatorVersion: Int,
        argsBlob: ByteArray,
        idempotencyRoot: String,
        createdAt: Long,
    ): MutationIntentRecord

    /** Inserts the one execution row paired with an intent. */
    public fun insertExecution(record: MutationExecutionRecord)

    /** Applies a ruled execution-state transition. */
    public fun advanceExecution(record: MutationExecutionRecord)

    /** Inserts one immutable semantic attempt generation. */
    public fun insertAttempt(record: MutationAttemptRecord)

    /** Writes the four conflict-receipt fields of an existing attempt exactly once. */
    public fun recordConflictReceipt(record: MutationAttemptRecord)

    /** Inserts a write-once acknowledgement; an exact duplicate is idempotent. */
    public fun insertAck(record: MutationAckRecord)

    /** Allocates an ID and appends normalized, UTF-8-bounded failure evidence. */
    public fun appendFailure(
        clientId: String,
        clientSequence: Long,
        generation: Int,
        kind: MutationFailureKind,
        detail: String,
        message: String,
        occurredAt: Long,
    ): MutationFailureRecord

    /** Inserts one initial `PENDING` effect. */
    public fun insertEffect(record: MutationEffectRecord)

    /** Advances one effect to a terminal disposition. */
    public fun advanceEffect(record: MutationEffectRecord)

    /** Inserts a pending alias edge; an exact duplicate is idempotent. */
    public fun insertAlias(record: MutationKeyAliasRecord)

    /** Activates a pending alias edge. */
    public fun advanceAlias(record: MutationKeyAliasRecord)

    /** Inserts one pending tombstone generation. */
    public fun insertTombstone(record: MutationKeyTombstoneRecord)

    /** Advances one tombstone generation forward. */
    public fun advanceTombstone(record: MutationKeyTombstoneRecord)

    /**
     * Removes eligible records at or below [serverConfirmedRetiredThroughSequence].
     *
     * The supplied prefix cannot exceed the client row's persisted server-confirmed prefix.
     * Alias redirects and active or pending tombstone generations are never removed by ordinary
     * prune. A superseded tombstone is eligible only when its creator is at/below this confirmed
     * prefix and its superseding intent is also at/below that intent's owning client's persisted
     * server-confirmed prefix.
     */
    public fun prune(
        clientId: String,
        serverConfirmedRetiredThroughSequence: Long,
    )
}
