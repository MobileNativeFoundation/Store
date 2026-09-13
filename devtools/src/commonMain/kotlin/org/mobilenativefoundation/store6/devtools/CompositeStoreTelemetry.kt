package org.mobilenativefoundation.store6.devtools

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import kotlin.time.Duration

/**
 * Fans one [StoreTelemetry] installation out to several sinks in registration order.
 *
 * A logger and inspector monitor install as one builder line:
 * `telemetry(storeTelemetryOf(logger, monitor))`. Extension vocabularies and apps may share one
 * application sink without adding methods to the core interface. The constructor snapshots its
 * caller-owned list. Each sink honors the seam's never-throw contract; this class adds no
 * guarding.
 */
@ExperimentalStoreApi
@OptIn(DelicateStoreApi::class)
public class CompositeStoreTelemetry(
    sinks: List<StoreTelemetry>,
) : StoreTelemetry {
    private val sinks: List<StoreTelemetry> = sinks.toList()

    override fun onFetchStarted(key: StoreKey) {
        for (sink in sinks) sink.onFetchStarted(key)
    }

    override fun onFetchSucceeded(
        key: StoreKey,
        duration: Duration,
    ) {
        for (sink in sinks) sink.onFetchSucceeded(key, duration)
    }

    override fun onFetchFailed(
        key: StoreKey,
        error: StoreError,
        duration: Duration,
    ) {
        for (sink in sinks) sink.onFetchFailed(key, error, duration)
    }

    override fun onServe(
        key: StoreKey,
        origin: Origin,
    ) {
        for (sink in sinks) sink.onServe(key, origin)
    }

    override fun onInvalidated(key: StoreKey) {
        for (sink in sinks) sink.onInvalidated(key)
    }

    override fun onCleared(key: StoreKey) {
        for (sink in sinks) sink.onCleared(key)
    }
}

/** Creates a [StoreTelemetry] sink that fans out to [sinks] in registration order. */
@ExperimentalStoreApi
public fun storeTelemetryOf(vararg sinks: StoreTelemetry): StoreTelemetry =
    CompositeStoreTelemetry(sinks.toList())
