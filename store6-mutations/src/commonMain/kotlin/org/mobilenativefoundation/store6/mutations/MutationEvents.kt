@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreMeta

/**
 * Read-only, in-process advisory mutation telemetry (D4).
 *
 * Delivery is best-effort: replay `0`, extra buffer capacity `64`, oldest dropped on overflow,
 * emitted with non-blocking `tryEmit` only. Events may drop under pressure, a new collector
 * receives no history, and restart replays no completed events. This flow is never a drain,
 * acknowledgement, retry, or settlement protocol; durable truth remains `pending`,
 * `pendingWrites`, `deadLetters`, and the journal. No event carries a raw `Throwable` or
 * `StoreError`.
 */
@ExperimentalStoreApi
public sealed interface MutationEvent {
    /** The observation time in Unix epoch milliseconds. */
    @ExperimentalStoreApi
    public val occurredAtEpochMillis: Long
}

/**
 * An advisory event scoped to one mutation intent.
 *
 * [identity] is the normalized terminal pair stored when enqueue commits until an attempt
 * exists, then the immutable push identity for that generation. An acknowledgement that
 * introduces a canonical target does not rewrite an already-sent generation's event identity.
 */
@ExperimentalStoreApi
public sealed interface MutationIntentEvent : MutationEvent {
    /** The opaque public id assigned at enqueue. */
    @ExperimentalStoreApi
    public val mutationId: String

    /** The effective durable identity pair for this event's generation. */
    @ExperimentalStoreApi
    public val identity: MutationKeyIdentity
}

/** One intent durably entered the journal. */
@ExperimentalStoreApi
public class MutationEnqueued internal constructor(
    @ExperimentalStoreApi
    public override val mutationId: String,
    @ExperimentalStoreApi
    public override val identity: MutationKeyIdentity,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The durable per-client sequence allocated at enqueue. */
    @ExperimentalStoreApi
    public val clientSequence: Long,

    /** The registered mutator storage identity. */
    @ExperimentalStoreApi
    public val mutatorId: String,
) : MutationIntentEvent

/**
 * One transport invocation began. [attempt] is the one-based transport invocation ordinal,
 * attempted after the `INFLIGHT` transition becomes durable but before transport.
 */
@ExperimentalStoreApi
public class MutationAttempted internal constructor(
    @ExperimentalStoreApi
    public override val mutationId: String,
    @ExperimentalStoreApi
    public override val identity: MutationKeyIdentity,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The semantic generation being transmitted. */
    @ExperimentalStoreApi
    public val generation: Int,

    /** The one-based transport invocation ordinal for [generation]. */
    @ExperimentalStoreApi
    public val attempt: Int,
) : MutationIntentEvent

/** The backend reported a precondition conflict for one generation. */
@ExperimentalStoreApi
public class MutationConflictObserved internal constructor(
    @ExperimentalStoreApi
    public override val mutationId: String,
    @ExperimentalStoreApi
    public override val identity: MutationKeyIdentity,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The conflicted generation. */
    @ExperimentalStoreApi
    public val generation: Int,

    /** The server-reported metadata receipt, when one exists. */
    @ExperimentalStoreApi
    public val serverMeta: StoreMeta?,
) : MutationIntentEvent

/** A successful acknowledgement became durable. */
@ExperimentalStoreApi
public class MutationAcknowledged internal constructor(
    @ExperimentalStoreApi
    public override val mutationId: String,
    @ExperimentalStoreApi
    public override val identity: MutationKeyIdentity,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The acknowledged generation. */
    @ExperimentalStoreApi
    public val generation: Int,

    /** The authoritative presence the backend confirmed. */
    @ExperimentalStoreApi
    public val presence: MutationPresenceState,
) : MutationIntentEvent

/** A durable acknowledgement was adopted into the Store. */
@ExperimentalStoreApi
public class MutationAdopted internal constructor(
    @ExperimentalStoreApi
    public override val mutationId: String,
    @ExperimentalStoreApi
    public override val identity: MutationKeyIdentity,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The adopted generation. */
    @ExperimentalStoreApi
    public val generation: Int,

    /** The adopted authoritative presence. */
    @ExperimentalStoreApi
    public val presence: MutationPresenceState,
) : MutationIntentEvent

/** One durable invalidation effect reached its terminal `APPLIED` disposition. */
@ExperimentalStoreApi
public class MutationEffectApplied internal constructor(
    @ExperimentalStoreApi
    public override val mutationId: String,
    @ExperimentalStoreApi
    public override val identity: MutationKeyIdentity,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The generation whose effect completed. */
    @ExperimentalStoreApi
    public val generation: Int,

    /** The stable normalized index of the completed effect record. */
    @ExperimentalStoreApi
    public val effectIndex: Int,
) : MutationIntentEvent

/** One durable invalidation effect was terminally `SKIPPED` by conflict server-wins. */
@ExperimentalStoreApi
public class MutationEffectSkipped internal constructor(
    @ExperimentalStoreApi
    public override val mutationId: String,
    @ExperimentalStoreApi
    public override val identity: MutationKeyIdentity,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The generation whose effect was skipped. */
    @ExperimentalStoreApi
    public val generation: Int,

    /** The stable normalized index of the skipped effect record. */
    @ExperimentalStoreApi
    public val effectIndex: Int,
) : MutationIntentEvent

/** An active retryable failure that did not park. */
@ExperimentalStoreApi
public class MutationFailed internal constructor(
    @ExperimentalStoreApi
    public override val mutationId: String,
    @ExperimentalStoreApi
    public override val identity: MutationKeyIdentity,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The generation current when the failure was recorded. */
    @ExperimentalStoreApi
    public val generation: Int,

    /** The public active state retained by the failure. */
    @ExperimentalStoreApi
    public val state: MutationPendingState,

    /** The normalized failure evidence. */
    @ExperimentalStoreApi
    public val failure: MutationFailure,
) : MutationIntentEvent

/** One intent durably parked and left the executable FIFO. */
@ExperimentalStoreApi
public class MutationParked internal constructor(
    @ExperimentalStoreApi
    public override val mutationId: String,
    @ExperimentalStoreApi
    public override val identity: MutationKeyIdentity,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The generation current when the intent parked; 0 before first preparation. */
    @ExperimentalStoreApi
    public val generation: Int,

    /** The normalized terminal parked reason. */
    @ExperimentalStoreApi
    public val failure: MutationFailure,
) : MutationIntentEvent

/** One intent durably retired and the contiguous local high-water advanced. */
@ExperimentalStoreApi
public class MutationRetired internal constructor(
    @ExperimentalStoreApi
    public override val mutationId: String,
    @ExperimentalStoreApi
    public override val identity: MutationKeyIdentity,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The retired generation. */
    @ExperimentalStoreApi
    public val generation: Int,

    /** The contiguous locally retired prefix after this retirement. */
    @ExperimentalStoreApi
    public val retiredThroughSequence: Long,
) : MutationIntentEvent

/**
 * A validated and persisted retirement-checkpoint receipt (D12, D15b). Client-scoped: checkpoint
 * events intentionally do not fabricate a mutation id, key identity, or generation after all
 * mutations have retired.
 */
@ExperimentalStoreApi
public class MutationCheckpointConfirmed internal constructor(
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The stable installation/journal identity whose prefix was confirmed. */
    @ExperimentalStoreApi
    public val clientId: String,

    /** The prefix offered by the checkpoint request. */
    @ExperimentalStoreApi
    public val requestedThroughSequence: Long,

    /** The persisted server-confirmed prefix. */
    @ExperimentalStoreApi
    public val confirmedThroughSequence: Long,
) : MutationEvent

/**
 * A non-cancellation checkpoint transport, protocol, or persistence failure (D12). Client-scoped
 * and ephemeral: it creates no intent-owned failure row and invents no retired mutation
 * identity.
 */
@ExperimentalStoreApi
public class MutationCheckpointFailed internal constructor(
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The stable installation/journal identity whose checkpoint failed. */
    @ExperimentalStoreApi
    public val clientId: String,

    /** The prefix offered by the failed checkpoint request. */
    @ExperimentalStoreApi
    public val requestedThroughSequence: Long,

    /** The normalized ephemeral failure carrier. */
    @ExperimentalStoreApi
    public val failure: MutationFailure,
) : MutationEvent

/**
 * The internal advisory event bus backing `MutationStore.events`.
 *
 * TD-8 note: a `MutableSharedFlow` configured with [BufferOverflow] is legal advisory plumbing;
 * Channels and actors are banned as protocols. Emission is [tryEmit]-only, so lifecycle work can
 * never block or suspend on telemetry: replay `0`, extra buffer capacity `64`, and
 * [BufferOverflow.DROP_OLDEST] make every emission complete immediately, dropping the oldest
 * buffered event under pressure. Dropping an event never changes state; within one
 * accepted-state handoff, lifecycle events are attempted only after the corresponding state
 * becomes observable.
 */
internal class MutationEventBus {
    private val sink =
        MutableSharedFlow<MutationEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** The read-only advisory view republished by the facade. */
    internal val events: SharedFlow<MutationEvent> = sink.asSharedFlow()

    /** Non-blocking advisory emission; always completes immediately under DROP_OLDEST. */
    internal fun tryEmit(event: MutationEvent): Boolean = sink.tryEmit(event)
}
