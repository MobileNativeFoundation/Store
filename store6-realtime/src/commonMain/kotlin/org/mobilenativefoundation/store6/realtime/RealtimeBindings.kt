package org.mobilenativefoundation.store6.realtime

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.seam.runtime

/**
 * Creates an adopting binding that commits [RealtimeMessage.Upsert] through the engine write
 * handle and confirms [RealtimeMessage.Unchanged] without fetching.
 *
 * @throws IllegalArgumentException if `store.runtime()` is `null`. `FakeStore`, decorators, and
 * `MutationStore` withhold the handle. Use [invalidatingRealtimeBinding] in those cases.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> realtimeBinding(store: Store<K, V>): RealtimeBinding<K, V> {
    val runtime =
        store.runtime()
            ?: throw IllegalArgumentException(ADOPTING_BINDING_UNAVAILABLE)
    return RealtimeBinding(store, runtime.writeHandle)
}

/**
 * Creates a binding that never requires `store.runtime()`.
 *
 * [RealtimeMessage.Upsert] becomes `Store.invalidate(key)` so the next qualifying read
 * refetches. [RealtimeMessage.Unchanged] is ignored. Use this on `MutationStore`, whose facade
 * withholds the write handle, and on any other non-engine Store.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> invalidatingRealtimeBinding(
    store: Store<K, V>,
): RealtimeBinding<K, V> = RealtimeBinding(store, handle = null)

internal const val ADOPTING_BINDING_UNAVAILABLE: String =
    "realtimeBinding requires an engine-backed Store. " +
        "store.runtime() is null for FakeStore, decorators, and MutationStore. " +
        "Use invalidatingRealtimeBinding(store) when adoption is unavailable."
