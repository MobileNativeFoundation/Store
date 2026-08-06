package org.mobilenativefoundation.store6.core.seam

import org.mobilenativefoundation.store6.core.DelicateStoreApi
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.StoreKey
import kotlin.time.Duration

/**
 * Observes Store lifecycle events without participating in Store correctness.
 *
 * Handlers are non-suspending and must be non-blocking and must not throw. The engine never invokes
 * them while its state or write lock is held. [onServe] runs for every public data emission and
 * successful `get` return. For a revalidation it runs only when the rendered projection retains a
 * visible value: pass-through uses the effective authorized origin, an overlaid value uses
 * `Origin.OVERLAY`, and projected absence has no successful serve and therefore no hook.
 *
 * When telemetry is unset the engine retains a null reference, every call site short-circuits with
 * a null guard, and no fetch-duration mark is allocated. When configured, [onFetchStarted] runs at
 * fetch-coroutine start. [onFetchSucceeded] or [onFetchFailed] runs after commit or settlement and
 * before the fetch ticket completes, so every resumed waiter observes the terminal hook first.
 * Superseded fetches have no terminal hook.
 */
@ExperimentalStoreApi
@SubclassOptInRequired(DelicateStoreApi::class)
public interface StoreTelemetry {
    /** Observes the start of a fetch attempt for [key]. */
    public fun onFetchStarted(key: StoreKey) {}

    /** Observes successful fetch commit or revalidation for [key]. */
    public fun onFetchSucceeded(
        key: StoreKey,
        duration: Duration,
    ) {}

    /** Observes terminal fetch [error] for [key]. */
    public fun onFetchFailed(
        key: StoreKey,
        error: StoreError,
        duration: Duration,
    ) {}

    /** Observes a successful public serve of [key] from [origin]. */
    public fun onServe(
        key: StoreKey,
        origin: Origin,
    ) {}

    /** Observes successful invalidation of [key]. */
    public fun onInvalidated(key: StoreKey) {}

    /** Observes successful clearing of [key]. */
    public fun onCleared(key: StoreKey) {}
}
