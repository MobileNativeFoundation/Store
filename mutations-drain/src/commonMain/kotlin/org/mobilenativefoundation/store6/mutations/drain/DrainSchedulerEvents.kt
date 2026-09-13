package org.mobilenativefoundation.store6.mutations.drain

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/** An advisory scheduler lifecycle event scoped to a coordinator registration name. */
@ExperimentalStoreApi
public sealed interface DrainSchedulerEvent {
    /** The coordinator registration name. */
    @ExperimentalStoreApi
    public val storeName: String

    /** The observation time in Unix epoch milliseconds. */
    @ExperimentalStoreApi
    public val occurredAtEpochMillis: Long
}

/** A future activation was scheduled. */
@ExperimentalStoreApi
public class DrainActivationScheduled internal constructor(
    @ExperimentalStoreApi
    public override val storeName: String,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The requested delay in milliseconds. */
    @ExperimentalStoreApi
    public val delayMillis: Long,
) : DrainSchedulerEvent

/** A drain activation started its pass. */
@ExperimentalStoreApi
public class DrainActivationStarted internal constructor(
    @ExperimentalStoreApi
    public override val storeName: String,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,
) : DrainSchedulerEvent

/** A drain pass completed and reported the remaining journal counts. */
@ExperimentalStoreApi
public class DrainPassCompleted internal constructor(
    @ExperimentalStoreApi
    public override val storeName: String,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The number of nonterminal intents after the pass. */
    @ExperimentalStoreApi
    public val pendingIntents: Int,

    /** The number of terminal dead letters after the pass. */
    @ExperimentalStoreApi
    public val deadLetters: Int,
) : DrainSchedulerEvent

/** The pass itself failed because draining threw or its store was closed or unregistered. */
@ExperimentalStoreApi
public class DrainPassFailed internal constructor(
    @ExperimentalStoreApi
    public override val storeName: String,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The failure message. */
    @ExperimentalStoreApi
    public val message: String,
) : DrainSchedulerEvent

/** [DrainScheduler.schedule] threw, so no pending activation is tracked for the store. */
@ExperimentalStoreApi
public class DrainScheduleFailed internal constructor(
    @ExperimentalStoreApi
    public override val storeName: String,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,

    /** The failure message. */
    @ExperimentalStoreApi
    public val message: String,
) : DrainSchedulerEvent

/**
 * A tracked, still-pending request was cancelled when a registration was removed or a cleared pass
 * cancelled its safety activation. Replacing a request and a `CancellationException` inside a pass
 * do not emit this event.
 */
@ExperimentalStoreApi
public class DrainActivationCancelled internal constructor(
    @ExperimentalStoreApi
    public override val storeName: String,
    @ExperimentalStoreApi
    public override val occurredAtEpochMillis: Long,
) : DrainSchedulerEvent
