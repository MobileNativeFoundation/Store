package org.mobilenativefoundation.store6.core.seam

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreNamespace

/**
 * Persistence seam for the current nullable row associated with a [StoreKey].
 *
 * Implementations must uphold all of the following reader-liveness and mutation semantics:
 *
 * - Every collection of [reader] immediately first emits the current row, or `null` when absent.
 * - An active collection emits every subsequent change made through this instance, including a
 *   write equal to the current value and any matching [delete], [deleteNamespace], or [deleteAll]
 *   as `null`. Emissions may be conflated.
 * - A [reader] collection never completes normally. A non-cancellation collection failure is
 *   permitted; the engine retries it, and each new attempt again starts with the current row.
 *   Collection cancellation propagates.
 * - On normal return, [write], [delete], [deleteNamespace], and [deleteAll] provide
 *   read-your-writes: a subsequent [reader] collection starts with the applied row or absence, and
 *   each mutation's current-row notification has been published to every matching active
 *   collection. Those notifications may still be queued in downstream operators. A mutation may
 *   publish intermediate rows (including `null`), but the notification of each applied row or
 *   absence must be that mutation's final notification for that row before normal return.
 *   Therefore a notification that supersedes a successfully returned mutation is ordered after
 *   the return boundary.
 * - Mutation completion is exception-atomic for every [Throwable], including
 *   `CancellationException`: normal return means the mutation was applied, while throwing means
 *   it was not applied.
 *
 * Reactivity to changes made through another source-of-truth instance is implementation-specific.
 * External changes made while no [reader] is collected must appear in the next collection's first
 * emission; the engine's memory fast path may serve the previously observed value until that
 * collection begins.
 *
 * `clearCache` is deliberately absent because cache clearing is not a persistence mutation.
 *
 * @param K the key type used to locate a row
 * @param V the non-null row type
 */
@ExperimentalStoreApi
@SubclassOptInRequired(DelicateStoreApi::class)
public interface SourceOfTruth<K : StoreKey, V : Any> {
    /** Returns the live row stream for [key] under the contract documented on this interface. */
    public fun reader(key: K): Flow<V?>

    /** Persists [value] for [key] under the mutation contract documented on this interface. */
    public suspend fun write(
        key: K,
        value: V,
    )

    /** Destructively removes the row for [key] under the documented mutation contract. */
    public suspend fun delete(key: K)

    /**
     * Destructively removes every existing row in [namespace].
     *
     * Matching active [reader] collections receive `null` and remain live for later writes. On
     * normal return, all matching rows have been removed and their `null` notifications published.
     * Cancellation and all other failures preserve exception atomicity under the interface
     * mutation contract.
     */
    public suspend fun deleteNamespace(namespace: StoreNamespace)

    /**
     * Destructively removes every existing row in every namespace.
     *
     * All active [reader] collections receive `null` and remain live for later writes. On normal
     * return, all rows have been removed and their `null` notifications published. Cancellation
     * and all other failures preserve exception atomicity under the interface mutation contract.
     */
    public suspend fun deleteAll()
}
