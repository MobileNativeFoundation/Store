@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.conflicts

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.mutations.MutationConflictBuilder

/**
 * Registers [MutationMerges.serverWins] via [MutationConflictBuilder.merge].
 *
 * This function never registers a precondition selector, so it composes with a caller-supplied
 * `precondition { }` in the same conflict block.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> MutationConflictBuilder<K, V>.serverWins() {
    merge(MutationMerges.serverWins())
}

/**
 * Registers [MutationMerges.clientWins] via [MutationConflictBuilder.merge].
 *
 * This function never registers a precondition selector, so it composes with a caller-supplied
 * `precondition { }` in the same conflict block.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> MutationConflictBuilder<K, V>.clientWins() {
    merge(MutationMerges.clientWins())
}

/**
 * Registers [MutationMerges.lastWriteWins] via [MutationConflictBuilder.merge].
 *
 * This function never registers a precondition selector, so it composes with a caller-supplied
 * `precondition { }` in the same conflict block.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> MutationConflictBuilder<K, V>.lastWriteWins(
    onMineAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
    onTheirsAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
    writtenAt: (V) -> Long,
) {
    merge(
        MutationMerges.lastWriteWins(
            onMineAbsent = onMineAbsent,
            onTheirsAbsent = onTheirsAbsent,
            writtenAt = writtenAt,
        ),
    )
}

/**
 * Registers [MutationMerges.threeWay] via [MutationConflictBuilder.merge].
 *
 * This function never registers a precondition selector, so it composes with a caller-supplied
 * `precondition { }` in the same conflict block.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> MutationConflictBuilder<K, V>.threeWayMerge(
    onMineAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
    onTheirsAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
    merge: (base: V?, mine: V, theirs: V) -> V,
) {
    this.merge(
        MutationMerges.threeWay(
            onMineAbsent = onMineAbsent,
            onTheirsAbsent = onTheirsAbsent,
            merge = merge,
        ),
    )
}

/**
 * Registers [MutationMerges.fields] via [MutationConflictBuilder.merge].
 *
 * The extension name `mergeFields` pairs with the factory name `fields`.
 *
 * This function never registers a precondition selector, so it composes with a caller-supplied
 * `precondition { }` in the same conflict block.
 */
@ExperimentalStoreApi
public fun <K : StoreKey, V : Any> MutationConflictBuilder<K, V>.mergeFields(
    onMineAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
    onTheirsAbsent: MutationConflictBias = MutationConflictBias.THEIRS,
    onBothChanged: MutationConflictBias = MutationConflictBias.THEIRS,
    configure: MutationFieldMergeBuilder<V>.() -> Unit,
) {
    this.merge(
        MutationMerges.fields(
            onMineAbsent = onMineAbsent,
            onTheirsAbsent = onTheirsAbsent,
            onBothChanged = onBothChanged,
            configure = configure,
        ),
    )
}
