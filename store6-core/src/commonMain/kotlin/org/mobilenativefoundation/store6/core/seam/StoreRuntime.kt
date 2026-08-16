package org.mobilenativefoundation.store6.core.seam

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.internal.RealStore

/**
 * Optional engine-backed capabilities exposed to Store extensions without implementation downcasts.
 *
 * @param K the key type accepted by the owning Store
 * @param V the non-null value type produced by the owning Store
 */
@ExperimentalStoreApi
@SubclassOptInRequired(DelicateStoreApi::class)
public interface StoreRuntime<K : StoreKey, V : Any> {
    /** Engine-backed acknowledgement and freshness capability. */
    public val writeHandle: StoreWriteHandle<K, V>

    /** Best-effort advisory engine events; this hot flow never completes, even after Store close. */
    public val keyEvents: Flow<KeyEvents>

    /** Exact telemetry sink configured at build time, or `null` when telemetry is unset. */
    public val telemetry: StoreTelemetry?
}

/**
 * Returns this Store's engine-backed capability handle.
 *
 * Non-engine stores, fakes, and decorators return `null`; a decorator exposes its own affordances.
 * The single unchecked cast is contained inside the library.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> Store<K, V>.runtime(): StoreRuntime<K, V>? {
    @Suppress("UNCHECKED_CAST")
    return (this as? RealStore<K, V>)?.runtime
}
