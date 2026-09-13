package org.mobilenativefoundation.store6.mutations.drain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/**
 * Delay policy between scheduled activations for the same store, applied by the coordinator's
 * journal-based follow-up derivation. For `attempt >= 1`, the delay is
 * `min(initialDelay * multiplier^(attempt - 1), maxDelay)`. The same values drive the no-progress
 * escalation floor. With [multiplier] equal to `1.0`, the floor stays constant at [initialDelay]
 * instead of growing toward [maxDelay], so the delay remains bounded and positive rather than
 * becoming a hot loop.
 *
 * [initialDelay] must be strictly positive and finite. [maxDelay] must be finite and greater
 * than or equal to [initialDelay]. [multiplier] must be finite and greater than or equal to
 * `1.0`. Invalid values throw [IllegalArgumentException].
 */
@ExperimentalStoreApi
public class DrainBackoff(
    @ExperimentalStoreApi
    public val initialDelay: Duration = 30.seconds,
    @ExperimentalStoreApi
    public val multiplier: Double = 2.0,
    @ExperimentalStoreApi
    public val maxDelay: Duration = 1.hours,
) {
    init {
        require(initialDelay > Duration.ZERO && initialDelay.isFinite()) {
            "initialDelay must be positive and finite; was $initialDelay."
        }
        require(maxDelay.isFinite() && maxDelay >= initialDelay) {
            "maxDelay must be finite and >= initialDelay; was $maxDelay."
        }
        require(multiplier.isFinite() && multiplier >= 1.0) {
            "multiplier must be finite and >= 1.0; was $multiplier."
        }
    }

    /**
     * The follow-up delay for a per-identity head whose completed network attempts for
     * the current generation number [attempt]; [attempt] >= 1. Grows by [multiplier]
     * per attempt from [initialDelay], capped at [maxDelay].
     */
    @ExperimentalStoreApi
    public fun delayFor(attempt: Int): Duration {
        require(attempt >= 1) { "attempt must be >= 1; was $attempt." }
        if (multiplier == 1.0) return initialDelay
        var delay = initialDelay
        repeat(attempt - 1) {
            if (delay >= maxDelay) return maxDelay
            delay = delay * multiplier
        }
        return minOf(delay, maxDelay)
    }
}
