@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.devtools

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import kotlin.time.Duration

/**
 * Event-derived key state, not engine freshness authority.
 *
 * Policy or `MaxAge` staleness emits no event and is not inferred. [FRESH] means only that no
 * invalidation, clear, or failure has been observed since the latest success.
 */
@ExperimentalStoreApi
public enum class DevtoolsKeyState {
    /** A fetch start has been observed without a later state-changing event. */
    FETCHING,

    /** A success has been observed without a later invalidation, clear, or failure. */
    FRESH,

    /** An invalidation has been observed. */
    STALE,

    /** A fetch failure has been observed. */
    ERROR,

    /** A clear has been observed. */
    CLEARED,

    /** The key has been observed without an event that establishes another state. */
    OBSERVED,
}

/** Immutable event-derived summary for one namespaced key. */
@ExperimentalStoreApi
public class DevtoolsKeyEntry internal constructor(
    /** Stable namespace component of the key identity. */
    public val namespace: String,
    /** Stable canonical identifier within [namespace]. */
    public val key: String,
    /** Latest state derived from the monitor's event vocabulary. */
    public val state: DevtoolsKeyState,
    /** Most recently observed successful serve origin. */
    public val lastOrigin: Origin?,
    /** Event time of the most recently observed successful fetch, when retained by state. */
    public val lastFetchSucceededAt: Duration?,
    /** Most recently observed fetch failure, when any. */
    public val lastError: StoreError?,
    /** Number of fetch starts observed for this identity. */
    public val fetchCount: Long,
    /** Number of successful serves observed for this identity. */
    public val serveCount: Long,
) {
    internal fun update(
        state: DevtoolsKeyState = this.state,
        lastOrigin: Origin? = this.lastOrigin,
        lastFetchSucceededAt: Duration? = this.lastFetchSucceededAt,
        lastError: StoreError? = this.lastError,
        fetchCount: Long = this.fetchCount,
        serveCount: Long = this.serveCount,
    ): DevtoolsKeyEntry =
        DevtoolsKeyEntry(
            namespace = namespace,
            key = key,
            state = state,
            lastOrigin = lastOrigin,
            lastFetchSucceededAt = lastFetchSucceededAt,
            lastError = lastError,
            fetchCount = fetchCount,
            serveCount = serveCount,
        )
}

/** Immutable point-in-time devtools projection. */
@ExperimentalStoreApi
public class DevtoolsSnapshot internal constructor(
    keys: List<DevtoolsKeyEntry>,
    events: List<StoreDevtoolsEvent>,
    /** Number of events dropped from the bounded log since the last [StoreDevtoolsMonitor.clearLog]. */
    public val droppedEvents: Long,
    /** Latest assigned sequence, including events removed from the log. */
    public val lastSeq: Long,
) {
    /** Key summaries sorted by `(namespace, key)`. */
    public val keys: List<DevtoolsKeyEntry> = ImmutableSnapshotList(keys)

    /** Retained events ordered oldest to newest. */
    public val events: List<StoreDevtoolsEvent> = ImmutableSnapshotList(events)
}

private class ImmutableSnapshotList<T>(
    values: List<T>,
) : AbstractList<T>() {
    private val backing: List<T> = values.toMutableList()

    override val size: Int
        get() = backing.size

    override fun get(index: Int): T = backing[index]
}

internal fun deriveKeyEntry(
    previous: DevtoolsKeyEntry?,
    event: StoreDevtoolsEvent,
): DevtoolsKeyEntry {
    val current = previous ?: DevtoolsKeyEntry(
        namespace = event.namespace,
        key = event.key,
        state = DevtoolsKeyState.OBSERVED,
        lastOrigin = null,
        lastFetchSucceededAt = null,
        lastError = null,
        fetchCount = 0,
        serveCount = 0,
    )
    return when (event) {
        is StoreDevtoolsEvent.FetchStarted -> current.update(
            state = DevtoolsKeyState.FETCHING,
            fetchCount = current.fetchCount + 1,
        )

        is StoreDevtoolsEvent.FetchSucceeded -> current.update(
            state = DevtoolsKeyState.FRESH,
            lastFetchSucceededAt = event.at,
            lastError = null,
        )

        is StoreDevtoolsEvent.FetchFailed -> current.update(
            state = DevtoolsKeyState.ERROR,
            lastError = event.error,
        )

        is StoreDevtoolsEvent.Served -> current.update(
            lastOrigin = event.origin,
            serveCount = current.serveCount + 1,
        )

        is StoreDevtoolsEvent.Invalidated -> current.update(state = DevtoolsKeyState.STALE)
        is StoreDevtoolsEvent.Cleared -> current.update(
            state = DevtoolsKeyState.CLEARED,
            lastFetchSucceededAt = null,
        )
    }
}
