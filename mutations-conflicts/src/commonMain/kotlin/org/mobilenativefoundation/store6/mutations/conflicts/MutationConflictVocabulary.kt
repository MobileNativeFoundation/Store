@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.mutations.conflicts

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.mutations.MutationConflictResolution
import org.mobilenativefoundation.store6.mutations.MutationPresence

/**
 * The merge function shape accepted by MutationConflictBuilder.merge. Kotlin typealiases are
 * transparent, so values of this type install directly. Parameter names are documentation only.
 */
@ExperimentalStoreApi
public typealias MutationMergeFunction<V> =
    (
        base: MutationPresence<V>,
        mine: MutationPresence<V>,
        theirs: MutationPresence<V>,
    ) -> MutationConflictResolution<V>

/**
 * Which side a policy prefers when a rule must pick one. MINE is this client's projected
 * outcome; THEIRS is the post-barrier recapture of local authoritative truth — not the conflict
 * response's payload and not StoreError.Conflict.serverMeta.
 */
@ExperimentalStoreApi
public enum class MutationConflictBias { MINE, THEIRS }
