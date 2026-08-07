package org.mobilenativefoundation.store6.core

import kotlin.time.Duration

/**
 * A per-call policy describing how fresh a value must be before it is served.
 *
 * Each read is planned from resident availability, invalidation state, typed metadata, and this
 * policy. Concurrent requests for one key still share a single in-flight fetch even when their
 * policies differ.
 */
public sealed interface Freshness {
    /**
     * The default policy: serve a locally available value immediately. Invalidated values and
     * source-of-truth rows without freshness metadata are served as stale while one background
     * revalidation runs; fetch when no local value exists.
     */
    public data object CachedOrFetch : Freshness

    /**
     * Serve a locally available value only when it has known freshness metadata, has not been
     * invalidated, and its age does not exceed [notOlderThan]; otherwise withhold it and fetch a
     * fresh value.
     */
    public class MaxAge(
        /** The oldest a served value may be before a fetch is required. */
        public val notOlderThan: Duration,
    ) : Freshness

    /**
     * Never serve a cached value; block until a fresh fetch succeeds and fail when it does not. A
     * source-of-truth row without freshness metadata is also withheld.
     */
    public data object MustBeFresh : Freshness

    /**
     * Prefer fresh data after invalidation or when a local value has no freshness metadata, but
     * fall back to that stale value when the fetch fails. A local value with current known
     * metadata is served without fetching.
     */
    public data object StaleIfError : Freshness

    /**
     * Never invoke the fetcher; serve only locally available data and report
     * [StoreError.Missing] when none exists.
     *
     * On a memory miss, the configured source of truth is probed once, so a pre-existing persisted
     * row is locally available data. The builder still requires a fetcher, but LocalOnly never
     * invokes it.
     */
    public data object LocalOnly : Freshness
}
