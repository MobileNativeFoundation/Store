package org.mobilenativefoundation.store6.composedemo

import androidx.compose.runtime.Composable
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.StoreNamespace
import org.mobilenativefoundation.store6.core.StoreResult

/**
 * Consumed by the CI compose-stability gate. Strict tier: concrete core classes — the shipped
 * stability conf must make these stable (store6-core is not compiled with the compose compiler,
 * so without the conf every core type is external-unstable). Iface tier: interface/abstract-typed
 * parameters; the gate posture for these follows the T6 calibration ruling recorded in
 * docs/v6/decisions/store6-compose-packaging.md.
 */
private fun consume(value: Any?) {
    check(value !== StabilityProbeMarker)
}

private object StabilityProbeMarker

@Composable fun ProbeStrictData(value: StoreResult.Data<String>) = consume(value)

@Composable fun ProbeStrictLoading(value: StoreResult.Loading) = consume(value)

@Composable fun ProbeStrictRevalidated(value: StoreResult.Revalidated) = consume(value)

@Composable fun ProbeStrictError(value: StoreResult.Error) = consume(value)

@Composable fun ProbeStrictMaxAge(value: Freshness.MaxAge) = consume(value)

@Composable fun ProbeStrictOrigin(value: Origin) = consume(value)

@Composable fun ProbeStrictNamespace(value: StoreNamespace) = consume(value)

@Composable fun ProbeStrictFetchError(value: StoreError.Fetch) = consume(value)

@Composable fun ProbeIfaceStoreResult(value: StoreResult<String>) = consume(value)

@Composable fun ProbeIfaceFreshness(value: Freshness) = consume(value)

@Composable fun ProbeIfaceStoreKey(value: StoreKey) = consume(value)

@Composable fun ProbeIfaceStoreMeta(value: StoreMeta) = consume(value)

@Composable fun ProbeIfaceStoreError(value: StoreError) = consume(value)
