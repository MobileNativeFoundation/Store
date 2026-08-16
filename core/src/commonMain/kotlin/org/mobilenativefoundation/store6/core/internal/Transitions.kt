package org.mobilenativefoundation.store6.core.internal

import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreMeta

/** An input applied to the immutable state for a canonical key. */
internal sealed interface KeyEvent {
    /** Requests a fetch, offering [fresh] as the ticket if no fetch is active. */
    class EnsureFetch(
        val fresh: FetchTicket,
    ) : KeyEvent

    /** Reports that the fetch represented by [ticket] produced [value] ready to commit. */
    class CommitFetch(
        val ticket: FetchTicket,
        val value: Any,
        val meta: StoreMeta,
    ) : KeyEvent

    /** Reports that the fetch represented by [ticket] revalidated the resident value. */
    class CommitRevalidated(
        val ticket: FetchTicket,
    ) : KeyEvent

    /**
     * Reports that the write handle confirmed [value] for direct source-of-truth commit; [ticket]
     * is the synthetic owner of the stamped SOT attribution.
     */
    class ApplyWrite(
        val ticket: FetchTicket,
        val value: Any,
        val meta: StoreMeta,
    ) : KeyEvent

    /** Reports that the fetch represented by [ticket] observed a server-side deletion. */
    class CommitDeleted(
        val ticket: FetchTicket,
    ) : KeyEvent

    /** Reports that the fetch represented by [ticket] reached a terminal outcome. */
    class SettleFetch(
        val ticket: FetchTicket,
    ) : KeyEvent

    /** Marks the key stale without removing its value. */
    data object Invalidate : KeyEvent

    /** Destructively removes the key's value and supersedes any in-flight fetch commit. */
    data object Clear : KeyEvent

    /** Returns and clears [observed] only when it is still the current consume-once tag. */
    class ConsumeAttribution(
        val observed: AttributionTag?,
    ) : KeyEvent

    /** Revokes the current consume-once attribution tag. */
    data object RevokeAttribution : KeyEvent
}

/** A side effect for the engine to interpret after a pure state transition. */
internal sealed interface KeyEffect {
    /** Launch a fetch owned by [ticket]. */
    class Launch(
        val ticket: FetchTicket,
    ) : KeyEffect

    /** Wait for the existing fetch represented by [ticket]. */
    class Join(
        val ticket: FetchTicket,
    ) : KeyEffect

    /** Accept the guarded fetch and stamp attribution; the engine persists, then converges it. */
    data object Commit : KeyEffect

    /** Refresh resident metadata inside the same critical section. */
    data object CommitRevalidation : KeyEffect

    /** Stamp SOT attribution; the engine writes the SoT and residence through under writeLock. */
    data object CommitWrite : KeyEffect

    /** Null out residence and forget bookkeeping: the server deleted the value. */
    data object CommitDelete : KeyEffect

    /** The fetch result was rejected because a clear advanced the clear epoch after launch. */
    data object Superseded : KeyEffect

    /** The key was marked stale; no residence change is required. */
    data object Invalidated : KeyEffect

    /** Null out residence inside the same critical section. */
    data object ClearResidence : KeyEffect

    /** The matching active fetch was settled. */
    data object Settled : KeyEffect

    /** The consume-once attribution returned by a consume event, if one was present. */
    class Consumed(
        val tag: AttributionTag?,
    ) : KeyEffect

    /** The consume-once attribution was revoked. */
    data object AttributionRevoked : KeyEffect

    /** The event did not apply to the current state. */
    data object Ignored : KeyEffect
}

/** The immutable state and engine effect produced by applying one [KeyEvent]. */
internal data class KeyTransition(
    val state: KeyState,
    val effect: KeyEffect,
)

/**
 * Applies [event] to [state] without performing suspension, I/O, or coroutine work.
 *
 * Ticket comparisons are identity checks so an older fetch can never commit into or settle a
 * newer fetch's slot. A successful commit settles the slot before its ordered source-of-truth
 * tail; another demand may reserve the next ticket, while the engine's write lock serializes both
 * mutations and the settled ticket's disposition preserves exact attribution until its outcome is
 * published. Epoch fields are monotone and are never reset by any event.
 */
internal fun transition(
    state: KeyState,
    event: KeyEvent,
): KeyTransition =
    when (event) {
        is KeyEvent.EnsureFetch ->
            when (val slot = state.fetch) {
                FetchSlot.Idle ->
                    KeyTransition(
                        state = state.copy(
                            fetch = FetchSlot.InFlight(
                                ticket = event.fresh,
                                clearEpochAtLaunch = state.clearEpoch,
                            ),
                        ),
                        effect = KeyEffect.Launch(event.fresh),
                    )

                is FetchSlot.InFlight ->
                    KeyTransition(state = state, effect = KeyEffect.Join(slot.ticket))
            }

        is KeyEvent.CommitFetch ->
            when (val slot = state.fetch) {
                is FetchSlot.InFlight ->
                    when {
                        slot.ticket !== event.ticket ->
                            KeyTransition(state = state, effect = KeyEffect.Ignored)

                        slot.clearEpochAtLaunch != state.clearEpoch ->
                            KeyTransition(state = state, effect = KeyEffect.Superseded)

                        else ->
                            KeyTransition(
                                state = state.copy(
                                    fetch = FetchSlot.Idle,
                                    attribution = AttributionTag(
                                        owner = event.ticket,
                                        value = event.value,
                                        origin = Origin.FETCHER,
                                        meta = event.meta,
                                        staleEpochAtCommit = state.staleEpoch,
                                    ),
                                ),
                                effect = KeyEffect.Commit,
                            )
                    }

                FetchSlot.Idle ->
                    KeyTransition(state = state, effect = KeyEffect.Ignored)
            }

        is KeyEvent.CommitRevalidated ->
            when (val slot = state.fetch) {
                is FetchSlot.InFlight ->
                    when {
                        slot.ticket !== event.ticket ->
                            KeyTransition(state = state, effect = KeyEffect.Ignored)

                        slot.clearEpochAtLaunch != state.clearEpoch ->
                            KeyTransition(state = state, effect = KeyEffect.Superseded)

                        else ->
                            KeyTransition(
                                state = state.copy(
                                    fetch = FetchSlot.Idle,
                                    attribution = null,
                                ),
                                effect = KeyEffect.CommitRevalidation,
                            )
                    }

                FetchSlot.Idle ->
                    KeyTransition(state = state, effect = KeyEffect.Ignored)
            }

        is KeyEvent.ApplyWrite ->
            KeyTransition(
                state = state.copy(
                    attribution = AttributionTag(
                        owner = event.ticket,
                        value = event.value,
                        origin = Origin.SOT,
                        meta = event.meta,
                        staleEpochAtCommit = state.staleEpoch,
                    ),
                ),
                effect = KeyEffect.CommitWrite,
            )

        is KeyEvent.CommitDeleted ->
            when (val slot = state.fetch) {
                is FetchSlot.InFlight ->
                    when {
                        slot.ticket !== event.ticket ->
                            KeyTransition(state = state, effect = KeyEffect.Ignored)

                        slot.clearEpochAtLaunch != state.clearEpoch ->
                            KeyTransition(state = state, effect = KeyEffect.Superseded)

                        else ->
                            KeyTransition(
                                // staleEpoch deliberately unchanged: a deleted key is absent-and-
                                // satisfied, so streams are not driven into a refetch loop; the
                                // next demand fetches. clearEpoch advances because the removal is
                                // destructive.
                                state = state.copy(
                                    fetch = FetchSlot.Idle,
                                    clearEpoch = state.clearEpoch + 1,
                                    readerGen = state.readerGen + 1,
                                    attribution = null,
                                ),
                                effect = KeyEffect.CommitDelete,
                            )
                    }

                FetchSlot.Idle ->
                    KeyTransition(state = state, effect = KeyEffect.Ignored)
            }

        is KeyEvent.SettleFetch ->
            when (val slot = state.fetch) {
                is FetchSlot.InFlight ->
                    if (slot.ticket === event.ticket) {
                        KeyTransition(
                            state = state.copy(fetch = FetchSlot.Idle),
                            effect =
                                if (slot.clearEpochAtLaunch != state.clearEpoch) {
                                    KeyEffect.Superseded
                                } else {
                                    KeyEffect.Settled
                                },
                        )
                    } else {
                        KeyTransition(state = state, effect = KeyEffect.Ignored)
                    }

                FetchSlot.Idle ->
                    KeyTransition(state = state, effect = KeyEffect.Ignored)
            }

        KeyEvent.Invalidate ->
            KeyTransition(
                state = state.copy(staleEpoch = state.staleEpoch + 1),
                effect = KeyEffect.Invalidated,
            )

        KeyEvent.Clear ->
            KeyTransition(
                state = state.copy(
                    clearEpoch = state.clearEpoch + 1,
                    staleEpoch = state.staleEpoch + 1,
                    readerGen = state.readerGen + 1,
                    attribution = null,
                ),
                effect = KeyEffect.ClearResidence,
            )

        is KeyEvent.ConsumeAttribution -> {
            val tag = state.attribution.takeIf { it === event.observed }
            KeyTransition(
                state = if (tag == null) state else state.copy(attribution = null),
                effect = KeyEffect.Consumed(tag),
            )
        }

        KeyEvent.RevokeAttribution ->
            KeyTransition(
                state =
                    if (state.attribution == null) {
                        state
                    } else {
                        state.copy(attribution = null)
                    },
                effect = KeyEffect.AttributionRevoked,
            )
    }
