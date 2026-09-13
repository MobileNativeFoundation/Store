package org.mobilenativefoundation.store6.mutations.drain

import kotlin.time.Duration
import org.mobilenativefoundation.store6.core.ExperimentalStoreApi

/** The truthful result of one coordinator pass, derived from journal state after the pass. */
@ExperimentalStoreApi
public sealed interface DrainPassOutcome {
    /**
     * No nonterminal intents remain and no retirement-checkpoint failure was observed during the
     * pass. Dead letters may exist because they are terminal.
     */
    @ExperimentalStoreApi
    public class Cleared : DrainPassOutcome

    /** Work remains, and the coordinator attempted to schedule a follow-up. */
    @ExperimentalStoreApi
    public class Remaining(
        /** Count of nonterminal intents after the pass, or 0 when only checkpoint work remains. */
        @ExperimentalStoreApi
        public val pendingIntents: Int,

        /**
         * The follow-up delay the coordinator scheduled, or null when [DrainScheduler.schedule]
         * threw. In the failure case, a [DrainScheduleFailed] event reports the failure, and the
         * next enqueue, manual trigger, or launch pass can recover.
         */
        @ExperimentalStoreApi
        public val scheduledDelay: Duration?,
    ) : DrainPassOutcome

    /**
     * The registration name is unavailable, the coordinator is closed, or its store is closed and
     * reports `IllegalStateException("Store is closed.")`. This can be transient during app
     * startup when a scheduler activation fires before host registration completes.
     */
    @ExperimentalStoreApi
    public class Unavailable(
        @ExperimentalStoreApi
        public val storeName: String,
        @ExperimentalStoreApi
        public val reason: String,
    ) : DrainPassOutcome
}
