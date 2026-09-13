package org.mobilenativefoundation.store6.extensionprobe

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import kotlin.time.Duration

/** Counts engine lifecycle events; the probe's proof that telemetry is implementable seam-only. */
@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
public class MetricsTelemetry : StoreTelemetry {
    public var fetchStarts: Int = 0
        private set
    public var fetchSuccesses: Int = 0
        private set
    public var fetchFailures: Int = 0
        private set
    public var serves: Int = 0
        private set
    public var invalidations: Int = 0
        private set
    public var clears: Int = 0
        private set

    override fun onFetchStarted(key: StoreKey) {
        fetchStarts++
    }

    override fun onFetchSucceeded(
        key: StoreKey,
        duration: Duration,
    ) {
        fetchSuccesses++
    }

    override fun onFetchFailed(
        key: StoreKey,
        error: StoreError,
        duration: Duration,
    ) {
        fetchFailures++
    }

    override fun onServe(
        key: StoreKey,
        origin: Origin,
    ) {
        serves++
    }

    override fun onInvalidated(key: StoreKey) {
        invalidations++
    }

    override fun onCleared(key: StoreKey) {
        clears++
    }
}
