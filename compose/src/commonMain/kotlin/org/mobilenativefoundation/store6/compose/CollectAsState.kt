package org.mobilenativefoundation.store6.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Freshness
import org.mobilenativefoundation.store6.core.Store
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreResult
import org.mobilenativefoundation.store6.core.seam.StoreResults

/**
 * Collects [Store.stream] for [key] as compose [State], starting at [StoreResult.Loading].
 * Collection restarts only when the store instance or the structural stream identity changes:
 * `(namespace.value, canonicalId(), freshness)` with [Freshness.MaxAge] compared by its duration —
 * a new-but-equal key or policy instance never restarts collection. Structurally equal consecutive
 * [StoreResult.Data] frames do not invalidate readers (see [storeResultMutationPolicy]; age
 * excluded). [valueEquivalence] is captured at first composition for a given restart identity.
 * Collection is scoped to the composition; for lifecycle-gated collection use
 * `collectAsStateWithLifecycle` on the CMP lifecycle tier.
 *
 * Closed-store behavior: calling this on a closed store fails the composition — [Store.stream]
 * throws [IllegalStateException] inside the launched effect (the stream is guarded both at call
 * and at collection start); a collection cancelled by [Store.close] ends as coroutine
 * cancellation. The close message is engine-internal diagnostic text, not ABI.
 */
@ExperimentalStoreApi
@Composable
public fun <K : StoreKey, V : Any> Store<K, V>.collectAsState(
    key: K,
    freshness: Freshness = Freshness.CachedOrFetch,
    valueEquivalence: (V, V) -> Boolean = { a, b -> a == b },
): State<StoreResult<V>> {
    val restartKey = streamRestartKey(key, freshness)
    val state = remember(this, restartKey) {
        mutableStateOf<StoreResult<V>>(StoreResults.loading(), storeResultMutationPolicy(valueEquivalence))
    }
    LaunchedEffect(this, restartKey) {
        stream(key, freshness).collect { state.value = it }
    }
    return state
}

/**
 * Collects a flow of store results as compose [State] beginning at [initial] (default
 * [StoreResults.loading]), holding it with [storeResultMutationPolicy] so structurally equal
 * consecutive [StoreResult.Data] frames skip recomposition while lifecycle results always land.
 * Collection restarts when the flow instance changes and is scoped to the composition. Calling
 * `store.stream(key)` inline at the call site allocates a new flow every recomposition and restarts
 * collection from [initial]; prefer the [Store]-receiver overloads, which restart only on real key
 * or freshness changes.
 */
@ExperimentalStoreApi
@Composable
public fun <V> Flow<StoreResult<V>>.collectAsStoreState(
    initial: StoreResult<V> = StoreResults.loading(),
    valueEquivalence: (V, V) -> Boolean = { a, b -> a == b },
): State<StoreResult<V>> {
    val state = remember(this) {
        mutableStateOf(initial, storeResultMutationPolicy(valueEquivalence))
    }
    LaunchedEffect(this) { collect { state.value = it } }
    return state
}

internal fun streamRestartKey(key: StoreKey, freshness: Freshness): Any =
    Triple(key.namespace.value, key.canonicalId(), freshnessToken(freshness))

private fun freshnessToken(freshness: Freshness): Any =
    when (freshness) {
        is Freshness.MaxAge -> "MaxAge:${freshness.notOlderThan}"
        // GUARD: every other `Freshness` variant is a `data object`, so instance identity IS a
        // stable token. MaxAge is the sole plain class with identity equality and must be
        // normalized by value. If core ever adds another non-singleton Freshness, add a branch
        // for it here — otherwise a new-but-equal instance silently restarts collection on every
        // recomposition, which is exactly the footgun the MaxAge branch exists to prevent.
        else -> freshness
    }
