package org.mobilenativefoundation.store6.core.seam

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.Origin
import org.mobilenativefoundation.store6.core.StoreKey

/**
 * Advisory notifications produced at Store engine writer points.
 *
 * This hierarchy is deliberately open rather than sealed, so consumers must retain an `else`
 * branch and future minor-version variants remain source- and binary-compatible. Constructors are
 * internal because only the engine produces events.
 *
 * Delivery is a best-effort hot stream with replay `0` and a bounded buffer of `64` that drops the
 * oldest event on overflow. Correctness must never depend on observing every event; durable facts
 * remain in engine state and bookkeeping. The flow never completes, including after `Store.close`;
 * collectors must scope collection to their own lifecycle.
 *
 * [Written] is produced after a fetch commit with [Origin.FETCHER] and after write-handle apply with
 * [Origin.SOT]. [Invalidated] is produced for per-key invalidation and for each swept resident in
 * namespace or global invalidation. [Deleted] is produced for per-key clear, a committed server
 * deletion, and once per engine in the authoritative first sweep of namespace or global clear.
 * Purge sweeps, nonresident watermark coverage, external source-of-truth changes, and superseded
 * fetches produce no event.
 */
@ExperimentalStoreApi
public abstract class KeyEvents internal constructor() {
    /** Key whose engine produced this advisory event. */
    public abstract val key: StoreKey

    /** Reports a successful writer commit with its installed `origin`. */
    public class Written internal constructor(
        override val key: StoreKey,
        public val origin: Origin,
    ) : KeyEvents()

    /** Reports a successful stale mark for `key`. */
    public class Invalidated internal constructor(
        override val key: StoreKey,
    ) : KeyEvents()

    /** Reports a successful destructive removal for `key`. */
    public class Deleted internal constructor(
        override val key: StoreKey,
    ) : KeyEvents()
}
