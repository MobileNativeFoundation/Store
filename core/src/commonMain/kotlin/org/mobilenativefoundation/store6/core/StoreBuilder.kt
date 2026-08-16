@file:OptIn(org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class)

package org.mobilenativefoundation.store6.core

import org.mobilenativefoundation.store6.core.internal.DEFAULT_MAX_IDLE_ENGINES
import org.mobilenativefoundation.store6.core.internal.InMemoryBookkeeper
import org.mobilenativefoundation.store6.core.internal.InMemorySourceOfTruth
import org.mobilenativefoundation.store6.core.internal.DefaultFreshnessValidator
import org.mobilenativefoundation.store6.core.internal.LambdaFetcher
import org.mobilenativefoundation.store6.core.internal.RealStore
import org.mobilenativefoundation.store6.core.internal.ResultFetcher
import org.mobilenativefoundation.store6.core.internal.SystemWallClock
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.FreshnessValidator
import org.mobilenativefoundation.store6.core.seam.Overlay
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import org.mobilenativefoundation.store6.core.seam.WallClock

/**
 * Creates a [Store] using the settings supplied by [configure].
 *
 * @param K the key type accepted by the store
 * @param V the non-null value type produced by the store
 * @param configure configuration applied before the store is created
 * @return the configured store
 * @throws IllegalArgumentException if no fetcher is configured
 */
public fun <K : StoreKey, V : Any> store(
    configure: StoreBuilder<K, V>.() -> Unit,
): Store<K, V> = StoreBuilder<K, V>().apply(configure).build()

/**
 * Configuration receiver for the [store] creation DSL.
 *
 * @param K the key type accepted by the store
 * @param V the non-null value type produced by the store
 */
public class StoreBuilder<K : StoreKey, V : Any> internal constructor() {
    @OptIn(ExperimentalStoreApi::class)
    private var fetcher: Fetcher<K, V>? = null

    @OptIn(ExperimentalStoreApi::class)
    private var sot: SourceOfTruth<K, V>? = null

    @OptIn(ExperimentalStoreApi::class)
    private var wallClock: WallClock = SystemWallClock

    @OptIn(ExperimentalStoreApi::class)
    private var bookkeeper: Bookkeeper = InMemoryBookkeeper()

    @OptIn(ExperimentalStoreApi::class)
    private var validator: FreshnessValidator = DefaultFreshnessValidator

    @OptIn(ExperimentalStoreApi::class)
    private var telemetry: StoreTelemetry? = null

    @OptIn(ExperimentalStoreApi::class)
    private var overlay: Overlay<K, V>? = null

    private var maxIdleKeys: Int = DEFAULT_MAX_IDLE_ENGINES

    /**
     * Configures the suspending function used to retrieve a value for a key.
     *
     * This is success-or-throw sugar for [fetcherOfResult]: a returned value becomes
     * [FetcherResult.Success], while a thrown exception propagates through the store's fetch-failure
     * path. The last registration wins across this function, [fetcherOfResult], and the regular-
     * interface [fetcher] overload.
     *
     * @param fetch the function that retrieves and returns a value for the supplied key
     */
    public fun fetcher(fetch: suspend (K) -> V) {
        this.fetcher = LambdaFetcher(fetch)
    }

    /**
     * Configures a fetcher that returns the full [FetcherResult] vocabulary.
     *
     * The name follows v5's `Fetcher.ofResult`. The regular-interface [fetcher] overload cannot
     * accept a lambda, so lambda-return-type inference remains unambiguous. The last registration
     * wins across all three fetcher install points.
     *
     * @param fetch the function that returns a rich result for the supplied key
     */
    public fun fetcherOfResult(fetch: suspend (K) -> FetcherResult<V>) {
        this.fetcher = ResultFetcher(fetch)
    }

    /**
     * Installs a regular-interface [Fetcher] that can receive conditional-request ETags.
     *
     * This overload is regular-interface-only: [Fetcher] is deliberately not a fun interface, so
     * lambda calls continue to resolve to the success-or-throw [fetcher] function. The last
     * registration wins across all three fetcher install points.
     *
     * @param fetcher the fetch source installed for this store
     */
    @ExperimentalStoreApi
    public fun fetcher(fetcher: Fetcher<K, V>) {
        this.fetcher = fetcher
    }

    /**
     * Bounds quiescent per-key engine residency.
     *
     * Engines whose key has active collectors, in-flight work, or an in-flight fetch are always
     * resident and are never evicted. Once a key becomes quiescent its engine parks in an LRU idle
     * set holding at most [count] engines; the eldest quiescent engine beyond the bound is destroyed.
     * Eviction discards only derived in-memory state — durable rows, freshness metadata, stale marks,
     * and watermarks live in the source of truth and bookkeeper, so a later read of an evicted key is
     * semantically identical to one that was never evicted. `0` destroys every engine at quiescence.
     *
     * @param count the maximum number of quiescent engines retained; must be >= 0. Default 128.
     */
    public fun maxIdleKeys(count: Int) {
        require(count >= 0) { "maxIdleKeys must be >= 0, was $count." }
        maxIdleKeys = count
    }

    /**
     * Selects the persistence seam for this store.
     *
     * The engine reads [SourceOfTruth.reader], writes successfully fetched values through
     * [SourceOfTruth.write], and treats [SourceOfTruth.delete] as destructive persistence removal.
     * When the stored selection is consumed by the engine, an absent block installs the internal
     * in-memory default. Custom implementations should be validated with the source-of-truth
     * contract kit.
     *
     * @param sot the source of truth used by the store
     */
    @ExperimentalStoreApi
    public fun persistence(sot: SourceOfTruth<K, V>) {
        this.sot = sot
    }

    /** Installs a non-blocking lifecycle observer; leaving it unset preserves the null fast path. */
    @ExperimentalStoreApi
    public fun telemetry(telemetry: StoreTelemetry) {
        this.telemetry = telemetry
    }

    /**
     * Installs the stream-only projection layer for this store.
     *
     * The last registration wins. Leaving this unset preserves the direct-residence fast path and
     * allocates no projection writer or readiness state.
     */
    @ExperimentalStoreApi
    public fun overlay(overlay: Overlay<K, V>) {
        this.overlay = overlay
    }

    /** Installs the durable freshness bookkeeping implementation used by this store. */
    @ExperimentalStoreApi
    public fun bookkeeper(bookkeeper: Bookkeeper) {
        this.bookkeeper = bookkeeper
    }

    /** Installs the wall clock used for age and freshness-bound calculations. */
    @ExperimentalStoreApi
    public fun wallClock(wallClock: WallClock) {
        this.wallClock = wallClock
    }

    /** Installs the policy planner used to select a fetch plan for each coherent read snapshot. */
    @ExperimentalStoreApi
    public fun freshnessValidator(validator: FreshnessValidator) {
        this.validator = validator
    }

    @OptIn(ExperimentalStoreApi::class)
    internal fun build(): Store<K, V> {
        val fetch = requireNotNull(fetcher) {
            "store<K, V> { } requires a fetcher { }, fetcherOfResult { }, or " +
                "fetcher(Fetcher) block."
        }
        val sourceOfTruth = sot ?: InMemorySourceOfTruth()
        return RealStore(
            fetch,
            sourceOfTruth,
            wallClock,
            bookkeeper,
            validator,
            telemetry,
            overlay,
            maxIdleKeys,
        )
    }
}
