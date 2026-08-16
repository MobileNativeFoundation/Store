package org.mobilenativefoundation.store6.extensionprobe

import org.mobilenativefoundation.store6.core.StoreError

/** Extension-owned journal outcomes; core telemetry deliberately carries no mutation vocabulary. */
public sealed interface MutationDrainEvent {
    /** Stable journal identity for the mutation whose drain attempt produced this event. */
    public val mutationId: String

    /** A drain attempt failed but remains eligible for the extension's retry policy. */
    public data class Failed(
        override val mutationId: String,
        public val error: StoreError,
    ) : MutationDrainEvent

    /** A drain attempt moved to extension-owned dead-letter handling. */
    public data class Parked(
        override val mutationId: String,
        public val error: StoreError,
    ) : MutationDrainEvent
}

/** Receives extension-owned mutation-drain events. */
public fun interface MutationEventSink {
    /** Records [event] according to the extension's delivery policy. */
    public fun onEvent(event: MutationDrainEvent)
}

/** Queries the extension-owned pending and dead-letter journal projections. */
public interface MutationJournalView<P : Any, D : Any> {
    /** Returns the currently pending journal rows. */
    public suspend fun pendingWrites(): List<P>

    /** Returns the currently parked journal rows. */
    public suspend fun deadLetters(): List<D>
}
