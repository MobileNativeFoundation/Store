package org.mobilenativefoundation.store6.compose

import androidx.compose.runtime.SnapshotMutationPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreResult

/**
 * Structural equivalence for snapshot state holding [StoreResult]: two [StoreResult.Data] are
 * equivalent iff origin, isStale, and refreshing match and [valueEquivalence] accepts the values.
 * [StoreResult.Data.age] is deliberately excluded: it advances on every emission and would defeat
 * recomposition skipping; derive live age from a clock when displaying it. Results of different
 * kinds are never equivalent, and lifecycle results (Loading, Revalidated, Error) are never
 * merged except as identical instances — a State is a conflated container, so event-shaped
 * consumption of Revalidated/Error must collect the Flow. This exists because StoreResult types
 * have identity equality by design (no equals override).
 *
 * The seam these semantics are written against is a FREEZE CANDIDATE, not frozen.
 */
@ExperimentalStoreApi
public fun <V> storeResultMutationPolicy(
    valueEquivalence: (V, V) -> Boolean = { a, b -> a == b },
): SnapshotMutationPolicy<StoreResult<V>> = object : SnapshotMutationPolicy<StoreResult<V>> {
    override fun equivalent(a: StoreResult<V>, b: StoreResult<V>): Boolean =
        structurallyEquivalent(a, b, valueEquivalence)
}

/**
 * Drops structurally-equal consecutive [StoreResult.Data] frames; every lifecycle result
 * (Loading, Revalidated, Error) always passes, and no result is ever dropped in favor of a
 * different kind. This mirrors the engine's `conflateLatestData` discipline as landed by the
 * issue-007 OQ-1 ruling — same-kind latest-wins, never merged across kinds — whose public
 * contract reads: "Revalidated is a lifecycle signal: `conflateLatestData` never conflates it
 * away in favor of another kind; for a blocked collector a newer `Revalidated` supersedes an
 * older queued one, so the kind itself is never lost." This operator is stricter still: it never
 * supersedes lifecycle results at all — only exact structural Data duplicates are dropped. Age
 * is excluded from the comparison (see [storeResultMutationPolicy]). This is a store6-compose
 * convenience for stateIn/ViewModel consumers; the engine's TD-8 operator rule
 * (conflateLatestData as its single custom operator) governs store6-core, not this module.
 */
@ExperimentalStoreApi
public fun <V> Flow<StoreResult<V>>.skipEqualData(
    valueEquivalence: (V, V) -> Boolean = { a, b -> a == b },
): Flow<StoreResult<V>> = flow {
    var previous: StoreResult<V>? = null
    collect { result ->
        val last = previous
        previous = result
        val duplicate = last is StoreResult.Data<*> && result is StoreResult.Data<*> &&
            structurallyEquivalent(last, result, valueEquivalence)
        if (!duplicate) emit(result)
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <V> structurallyEquivalent(
    a: StoreResult<V>,
    b: StoreResult<V>,
    valueEquivalence: (V, V) -> Boolean,
): Boolean {
    if (a === b) return true
    if (a !is StoreResult.Data<*> || b !is StoreResult.Data<*>) return false
    return a.origin == b.origin &&
        a.isStale == b.isStale &&
        a.refreshing == b.refreshing &&
        valueEquivalence(a.value as V, b.value as V)
}
