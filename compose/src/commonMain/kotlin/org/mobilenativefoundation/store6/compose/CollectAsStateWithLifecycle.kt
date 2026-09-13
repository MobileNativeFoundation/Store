package org.mobilenativefoundation.store6.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.StoreResults

/**
 * Lifecycle-gated [collectAsState]: collection runs only at or above [minActiveState] via
 * [repeatOnLifecycle], retains the last result while stopped, and re-collects [Store.stream]
 * from scratch on re-entry (the engine re-emits the current snapshot first, so the State
 * catches up without a Loading reset). Under the bounded key registry a paused collection
 * releases its engine refcount; a quiescent idle engine may be evicted (LRU, default
 * `maxIdleKeys` 128) and is transparently rebuilt on re-entry — no API-visible difference.
 * Requires a populated [LocalLifecycleOwner] (any CMP UI host or Android component provides
 * one) unless [lifecycleOwner] is passed explicitly. On targets with no UI host that populates
 * it — linuxX64, mingwX64 and the non-simulator Apple targets, in practice — pass
 * [lifecycleOwner] explicitly or use [collectAsState].
 */
@ExperimentalStoreApi
@Composable
public fun <K : StoreKey, V : Any> Store<K, V>.collectAsStateWithLifecycle(
    key: K,
    freshness: Freshness = Freshness.CachedOrFetch,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    valueEquivalence: (V, V) -> Boolean = { a, b -> a == b },
): State<StoreResult<V>> {
    val restartKey = streamRestartKey(key, freshness)
    val state = remember(this, restartKey) {
        mutableStateOf<StoreResult<V>>(StoreResults.loading(), storeResultMutationPolicy(valueEquivalence))
    }
    LaunchedEffect(this, restartKey, lifecycleOwner, minActiveState) {
        lifecycleOwner.repeatOnLifecycle(minActiveState) {
            stream(key, freshness).collect { state.value = it }
        }
    }
    return state
}

/** Lifecycle-gated [collectAsStoreState]; see [collectAsStateWithLifecycle]. */
@ExperimentalStoreApi
@Composable
public fun <V> Flow<StoreResult<V>>.collectAsStoreStateWithLifecycle(
    initial: StoreResult<V> = StoreResults.loading(),
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    minActiveState: Lifecycle.State = Lifecycle.State.STARTED,
    valueEquivalence: (V, V) -> Boolean = { a, b -> a == b },
): State<StoreResult<V>> {
    val state = remember(this) {
        mutableStateOf(initial, storeResultMutationPolicy(valueEquivalence))
    }
    LaunchedEffect(this, lifecycleOwner, minActiveState) {
        lifecycleOwner.repeatOnLifecycle(minActiveState) {
            collect { state.value = it }
        }
    }
    return state
}
