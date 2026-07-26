package org.mobilenativefoundation.store6.devtools

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreError
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import kotlin.time.Duration

/**
 * Identity-only event vocabulary derived from the six [StoreTelemetry] hooks.
 *
 * This v0 vocabulary is versioned but experimental. The Store 6.1 wire format is deliberately not
 * decided: values never cross this seam. The seam remains a freeze candidate and sign-off is held.
 */
@ExperimentalStoreApi
public sealed class StoreDevtoolsEvent {
    /** Monotonic sequence within one monitor. */
    public abstract val seq: Long

    /** Monotonic elapsed time since this monitor was created. */
    public abstract val at: Duration

    /** Stable namespace component of the observed key identity. */
    public abstract val namespace: String

    /** Stable canonical identifier within [namespace]. */
    public abstract val key: String

    /** A fetch attempt started. */
    public class FetchStarted internal constructor(
        override val seq: Long,
        override val at: Duration,
        override val namespace: String,
        override val key: String,
    ) : StoreDevtoolsEvent()

    /** A fetch attempt committed or revalidated successfully. */
    public class FetchSucceeded internal constructor(
        override val seq: Long,
        override val at: Duration,
        override val namespace: String,
        override val key: String,
        /** Duration reported by the telemetry seam. */
        public val fetchDuration: Duration,
    ) : StoreDevtoolsEvent()

    /** A fetch attempt settled with an error. */
    public class FetchFailed internal constructor(
        override val seq: Long,
        override val at: Duration,
        override val namespace: String,
        override val key: String,
        /** Structured error reported by the telemetry seam. */
        public val error: StoreError,
        /** Duration reported by the telemetry seam. */
        public val fetchDuration: Duration,
    ) : StoreDevtoolsEvent()

    /** A public read served a value. */
    public class Served internal constructor(
        override val seq: Long,
        override val at: Duration,
        override val namespace: String,
        override val key: String,
        /** Effective origin reported by the telemetry seam. */
        public val origin: Origin,
    ) : StoreDevtoolsEvent()

    /** The key was invalidated successfully. */
    public class Invalidated internal constructor(
        override val seq: Long,
        override val at: Duration,
        override val namespace: String,
        override val key: String,
    ) : StoreDevtoolsEvent()

    /** The key was cleared successfully. */
    public class Cleared internal constructor(
        override val seq: Long,
        override val at: Duration,
        override val namespace: String,
        override val key: String,
    ) : StoreDevtoolsEvent()
}
