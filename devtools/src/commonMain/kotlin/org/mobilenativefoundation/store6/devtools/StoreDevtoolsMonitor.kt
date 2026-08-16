package org.mobilenativefoundation.store6.devtools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Event-capacity-bounded, event-derived telemetry projection installed with `telemetry(monitor)`.
 *
 * One monitor may observe multiple stores; `(namespace, canonical key)` keeps their identities
 * distinct. This observer remains on the freeze-candidate telemetry seam. It inherits the handler
 * contract: calls are non-suspending, must be non-blocking, and must never throw.
 *
 * State is published with [StateFlow] compare-and-set updates, without locks, application-owned
 * atomics, or channels. [capacity] bounds retained events only. Key summaries survive event
 * eviction and [clearLog], retaining one entry per distinct observed identity, so installed memory
 * and hot-path work scale with distinct key cardinality plus event capacity. This cost is nonzero;
 * leaving telemetry unset preserves the engine's untouched null fast path. Every state is
 * event-derived and is not engine freshness authority.
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class StoreDevtoolsMonitor(
    private val capacity: Int = 500,
    timeSource: TimeSource = TimeSource.Monotonic,
) : StoreTelemetry {
    init {
        require(capacity > 0) { "capacity must be greater than zero, was $capacity." }
    }

    private val start = timeSource.markNow()
    private val mutableState = MutableStateFlow(
        DevtoolsSnapshot(
            keys = emptyList(),
            events = emptyList(),
            droppedEvents = 0,
            lastSeq = 0,
        ),
    )

    /** Current immutable projection. */
    public val state: StateFlow<DevtoolsSnapshot> = mutableState.asStateFlow()

    /** Returns monotonic elapsed time from the same clock and origin used by recorded events. */
    public fun elapsedNow(): Duration = start.elapsedNow()

    /** Clears retained events and drop accounting while preserving key summaries and sequence. */
    public fun clearLog() {
        mutableState.update { previous ->
            DevtoolsSnapshot(
                keys = previous.keys,
                events = emptyList(),
                droppedEvents = 0,
                lastSeq = previous.lastSeq,
            )
        }
    }

    override fun onFetchStarted(key: StoreKey) {
        record(key) { seq, at, namespace, canonicalId ->
            StoreDevtoolsEvent.FetchStarted(seq, at, namespace, canonicalId)
        }
    }

    override fun onFetchSucceeded(
        key: StoreKey,
        duration: Duration,
    ) {
        record(key) { seq, at, namespace, canonicalId ->
            StoreDevtoolsEvent.FetchSucceeded(seq, at, namespace, canonicalId, duration)
        }
    }

    override fun onFetchFailed(
        key: StoreKey,
        error: StoreError,
        duration: Duration,
    ) {
        record(key) { seq, at, namespace, canonicalId ->
            StoreDevtoolsEvent.FetchFailed(seq, at, namespace, canonicalId, error, duration)
        }
    }

    override fun onServe(
        key: StoreKey,
        origin: Origin,
    ) {
        record(key) { seq, at, namespace, canonicalId ->
            StoreDevtoolsEvent.Served(seq, at, namespace, canonicalId, origin)
        }
    }

    override fun onInvalidated(key: StoreKey) {
        record(key) { seq, at, namespace, canonicalId ->
            StoreDevtoolsEvent.Invalidated(seq, at, namespace, canonicalId)
        }
    }

    override fun onCleared(key: StoreKey) {
        record(key) { seq, at, namespace, canonicalId ->
            StoreDevtoolsEvent.Cleared(seq, at, namespace, canonicalId)
        }
    }

    private inline fun record(
        key: StoreKey,
        crossinline createEvent: (
            seq: Long,
            at: Duration,
            namespace: String,
            canonicalId: String,
        ) -> StoreDevtoolsEvent,
    ) {
        val namespace = key.namespace.value
        val canonicalId = key.canonicalId()
        mutableState.update { previous ->
            val event = createEvent(
                previous.lastSeq + 1,
                elapsedNow(),
                namespace,
                canonicalId,
            )
            val existingIndex = previous.keys.indexOfFirst {
                it.namespace == namespace && it.key == canonicalId
            }
            val existing = if (existingIndex >= 0) previous.keys[existingIndex] else null
            val updatedEntry = deriveKeyEntry(existing, event)
            val updatedKeys = if (existingIndex >= 0) {
                previous.keys.mapIndexed { index, entry ->
                    if (index == existingIndex) updatedEntry else entry
                }
            } else {
                val insertionIndex = previous.keys.indexOfFirst {
                    it.namespace > namespace ||
                        (it.namespace == namespace && it.key > canonicalId)
                }.let { if (it >= 0) it else previous.keys.size }
                previous.keys.take(insertionIndex) +
                    updatedEntry +
                    previous.keys.drop(insertionIndex)
            }
            val didDrop = previous.events.size >= capacity
            val retainedEvents = if (didDrop) {
                previous.events.drop(1) + event
            } else {
                previous.events + event
            }
            DevtoolsSnapshot(
                keys = updatedKeys,
                events = retainedEvents,
                droppedEvents = previous.droppedEvents + if (didDrop) 1 else 0,
                lastSeq = event.seq,
            )
        }
    }
}
