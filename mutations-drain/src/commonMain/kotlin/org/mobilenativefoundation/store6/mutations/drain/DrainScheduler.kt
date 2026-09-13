package org.mobilenativefoundation.store6.mutations.drain

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * A constraint-gated alarm. Implementations invoke the coordinator-bound activation handler for
 * [DrainRequest.storeName] when an activation fires. Firing is at-least-once: replays and
 * overlapping activations are permitted and safe because passes are idempotent and serialized per
 * store.
 *
 * Logical-slot invariant: the scheduler tracks at most one known pending request per
 * [DrainRequest.storeName]. [schedule] replaces the tracked pending request, and [cancel] cancels
 * it. Implementations may transiently hold duplicates, such as during recovery after process
 * death. These duplicates must be bounded and are harmless rather than forbidden.
 */
@ExperimentalStoreApi
public interface DrainScheduler {
    /**
     * Binds [coordinator] before any other member is called. This method is called exactly once by
     * [mutationDrainCoordinator]. A second call throws [IllegalStateException]. Calling [schedule]
     * or [cancel] before attachment also throws [IllegalStateException].
     */
    @ExperimentalStoreApi
    public fun attach(coordinator: MutationDrainCoordinator): Unit

    /**
     * Performs a fail-fast capability check at registration time. Implementations answer from a
     * static capability matrix without a scheduling dry run. Unsupported constraints throw
     * [IllegalArgumentException] naming the platform and unsupported constraint keys instead of
     * silently downgrading the request. This matches scheduler backends that reject unsupported
     * constraints at schedule time.
     */
    @ExperimentalStoreApi
    public fun validate(constraints: DrainConstraints): Unit

    /**
     * Requests one future activation. Constraint validity was established by [validate].
     * Infrastructure failures, including a dead scope or scheduler backend rejection, are
     * reported by throwing. The coordinator converts them to [DrainScheduleFailed] advisory
     * events and reports a null scheduled delay in the pass outcome.
     */
    @ExperimentalStoreApi
    public fun schedule(request: DrainRequest): Unit

    /** Cancels the tracked pending activation for [storeName], if any. */
    @ExperimentalStoreApi
    public fun cancel(storeName: String): Unit
}
