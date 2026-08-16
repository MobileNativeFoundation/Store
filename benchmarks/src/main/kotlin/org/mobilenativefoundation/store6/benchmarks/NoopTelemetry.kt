package org.mobilenativefoundation.store6.benchmarks

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry

/**
 * Configured-but-empty sink. Every hook keeps its interface-default no-op body. Comparing a store
 * built with this sink to one with telemetry unset estimates incremental configured-noop overhead
 * relative to the null fast path: non-null branches, the fetch-duration mark in
 * KeyEngine.launchFetch, and virtual dispatch into empty bodies.
 */
@OptIn(ExperimentalStoreApi::class, DelicateStoreApi::class)
internal object NoopTelemetry : StoreTelemetry
