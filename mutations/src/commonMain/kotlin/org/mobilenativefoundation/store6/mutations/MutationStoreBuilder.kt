@file:OptIn(
    org.mobilenativefoundation.store6.core.ExperimentalStoreApi::class,
)

package org.mobilenativefoundation.store6.mutations

import org.mobilenativefoundation.store6.core.ExperimentalStoreApi
import org.mobilenativefoundation.store6.core.StoreBuilder
import org.mobilenativefoundation.store6.core.StoreKey
import org.mobilenativefoundation.store6.core.StoreMeta
import org.mobilenativefoundation.store6.core.seam.Bookkeeper
import org.mobilenativefoundation.store6.core.seam.Fetcher
import org.mobilenativefoundation.store6.core.seam.FetcherResult
import org.mobilenativefoundation.store6.core.seam.FreshnessValidator
import org.mobilenativefoundation.store6.core.seam.SourceOfTruth
import org.mobilenativefoundation.store6.core.seam.StoreTelemetry
import org.mobilenativefoundation.store6.core.seam.WallClock
import org.mobilenativefoundation.store6.mutations.storage.InMemoryMutationJournalStorage
import org.mobilenativefoundation.store6.mutations.storage.MutationJournalStorage

/**
 * Configuration receiver for the [mutationStore] creation DSL.
 *
 * Mirrors the optional core [StoreBuilder] doors a mutation store may configure and deliberately
 * exposes no `overlay` door: the mutation engine's overlay is a mutation store's sole projection
 * layer and is installed by the factory. The required mutation inputs — registry, server, key
 * resolver, and value codec/version — are [mutationStore] factory parameters, never builder
 * doors.
 *
 * The builder retains the exact selected-or-default [SourceOfTruth] and [Bookkeeper] so the
 * factory forwards the same instances to both the delegated core Store and the mutation engine.
 * When either seam is left unset, a mutations-owned contract-certified default is created for it
 * and that same instance is passed to both sides; core's inaccessible internal defaults are never
 * silently substituted.
 */
@ExperimentalStoreApi
public class MutationStoreBuilder<K : StoreKey, V : Any> internal constructor() {
    private var installFetcher: (StoreBuilder<K, V>.() -> Unit)? = null
    private var sot: SourceOfTruth<K, V>? = null
    private var bookkeeper: Bookkeeper? = null
    private var telemetry: StoreTelemetry? = null
    private var wallClock: WallClock? = null
    private var validator: FreshnessValidator? = null
    private var maxIdleKeys: Int? = null
    private var conflicts: MutationConflictRegistration<K, V>? = null
    private var journalStorage: MutationJournalStorage? = null
    private var snapshot: MutationStoreConfiguration<K, V>? = null

    /**
     * Configures the suspending function used to retrieve a value for a key.
     *
     * Success-or-throw sugar forwarded to the core builder's identical door. The last registration
     * wins across this function, [fetcherOfResult], and the regular-interface [fetcher] overload.
     *
     * @param fetch the function that retrieves and returns a value for the supplied key
     */
    @ExperimentalStoreApi
    public fun fetcher(fetch: suspend (K) -> V) {
        installFetcher = { this.fetcher(fetch) }
    }

    /**
     * Configures a fetcher that returns the full [FetcherResult] vocabulary.
     *
     * Forwarded to the core builder's identical door. The last registration wins across all three
     * fetcher install points.
     *
     * @param fetch the function that returns a rich result for the supplied key
     */
    @ExperimentalStoreApi
    public fun fetcherOfResult(fetch: suspend (K) -> FetcherResult<V>) {
        installFetcher = { this.fetcherOfResult(fetch) }
    }

    /**
     * Installs a regular-interface [Fetcher] that can receive conditional-request ETags.
     *
     * Forwarded to the core builder's identical door. The last registration wins across all three
     * fetcher install points.
     *
     * @param fetcher the fetch source installed for this store
     */
    @ExperimentalStoreApi
    public fun fetcher(fetcher: Fetcher<K, V>) {
        installFetcher = { this.fetcher(fetcher) }
    }

    /**
     * Selects the persistence seam for this mutation store.
     *
     * The exact instance is retained and forwarded to both the delegated core Store and the
     * mutation engine; leaving this unset installs one mutations-owned in-memory default on both
     * sides. Custom implementations should be validated with the source-of-truth contract kit.
     *
     * @param sot the source of truth used by the store and retained for the mutation engine
     */
    @ExperimentalStoreApi
    public fun persistence(sot: SourceOfTruth<K, V>) {
        this.sot = sot
    }

    /**
     * Installs the durable freshness bookkeeping implementation used by this mutation store.
     *
     * The exact instance is retained and forwarded to both the delegated core Store and the
     * mutation engine, so mutation metadata capture reads the same authority the Store updates.
     * Leaving this unset installs one mutations-owned in-memory default on both sides.
     *
     * @param bookkeeper the bookkeeper used by the store and retained for the mutation engine
     */
    @ExperimentalStoreApi
    public fun bookkeeper(bookkeeper: Bookkeeper) {
        this.bookkeeper = bookkeeper
    }

    /** Installs a non-blocking lifecycle observer; leaving it unset preserves the null fast path. */
    @ExperimentalStoreApi
    public fun telemetry(telemetry: StoreTelemetry) {
        this.telemetry = telemetry
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

    /**
     * Bounds quiescent per-key engine residency, forwarded to the core builder's identical door.
     *
     * @param count the maximum number of quiescent engines retained; must be >= 0. Default 128.
     */
    @ExperimentalStoreApi
    public fun maxIdleKeys(count: Int) {
        require(count >= 0) { "maxIdleKeys must be >= 0, was $count." }
        maxIdleKeys = count
    }

    /**
     * Registers the conflict policy for this mutation store.
     *
     * Without a registered merge, server-wins is the non-removable terminal. The last
     * registration of this door wins, mirroring every other builder door; within one
     * [MutationConflictBuilder] block each policy registers at most once.
     *
     * @param configure conflict policy registration applied before the store is created
     */
    @ExperimentalStoreApi
    public fun conflicts(configure: MutationConflictBuilder<K, V>.() -> Unit) {
        conflicts = MutationConflictBuilder<K, V>().apply(configure).build()
    }

    /**
     * Selects the durable mutation-journal storage for this store.
     *
     * The exact instance is retained by the mutation engine. Leaving this door unset creates one
     * mutations-owned [InMemoryMutationJournalStorage], so the journal stays in memory.
     * Reopening a store over the same durable instance enables restart hydration.
     *
     * @param storage the journal storage instance owned by this mutation store
     */
    @ExperimentalStoreApi
    public fun journalStorage(storage: MutationJournalStorage) {
        journalStorage = storage
    }

    /**
     * Materializes the retained configuration exactly once.
     *
     * The first call creates the mutations-owned defaults for any unset seam; every later call
     * returns the same snapshot, so the factory and any verification path observe identical
     * retained instances.
     */
    internal fun snapshot(): MutationStoreConfiguration<K, V> {
        snapshot?.let { return it }
        val installFetcher = requireNotNull(installFetcher) {
            "mutationStore<K, V> { } requires a fetcher { }, fetcherOfResult { }, or " +
                "fetcher(Fetcher) door."
        }
        return MutationStoreConfiguration(
            installFetcher = installFetcher,
            sourceOfTruth = sot ?: MutationSourceOfTruth<K, V>(),
            bookkeeper = bookkeeper ?: MutationBookkeeper(),
            telemetry = telemetry,
            wallClock = wallClock,
            freshnessValidator = validator,
            maxIdleKeys = maxIdleKeys,
            conflicts = conflicts,
            journalStorage = journalStorage ?: InMemoryMutationJournalStorage(),
        ).also { snapshot = it }
    }
}

/**
 * Registers the conflict policy surface.
 *
 * Registration is validated as it happens: each policy registers at most once per block. There is
 * deliberately no terminal setter: server-wins is always present.
 */
@ExperimentalStoreApi
public class MutationConflictBuilder<K : StoreKey, V : Any> internal constructor() {
    private var precondition: ((MutationPreconditionCandidate<K, V>) -> StoreMeta?)? = null
    private var merge: (
        (
            base: MutationPresence<V>,
            mine: MutationPresence<V>,
            theirs: MutationPresence<V>,
        ) -> MutationConflictResolution<V>
    )? = null

    /**
     * Registers the pure precondition metadata selector.
     *
     * The selector receives only the library-owned [MutationPreconditionCandidate]; it never
     * receives or mutates a final push. Returning null deliberately selects an existence/value
     * precondition without metadata — it never removes the base precondition. Without a selector,
     * Store6 selects the candidate's captured metadata. The selector runs once per newly prepared
     * semantic generation, never on a transport retry.
     *
     * @param select the pure selector applied to each prepared generation's candidate
     */
    @ExperimentalStoreApi
    public fun precondition(select: (MutationPreconditionCandidate<K, V>) -> StoreMeta?) {
        require(this.precondition == null) {
            "conflicts { } already registered a precondition selector."
        }
        this.precondition = select
    }

    /**
     * Registers the pure merge policy consulted on a server-signaled conflict.
     *
     * The merge returns [MutationConflictResolution.Retry] or
     * [MutationConflictResolution.ServerWins] explicitly. When no merge is installed, server-wins
     * is the non-removable terminal.
     *
     * Retrying is bounded: on the third consecutive conflict receipt carrying identical server
     * metadata, the intent parks with a normalized `CONFLICT` failure instead of preparing
     * another generation. A merge that throws parks the intent as well.
     *
     * @param merge the pure policy applied to the captured base, this client's value, and the
     * recaptured authoritative value
     */
    @ExperimentalStoreApi
    public fun merge(
        merge: (
            base: MutationPresence<V>,
            mine: MutationPresence<V>,
            theirs: MutationPresence<V>,
        ) -> MutationConflictResolution<V>,
    ) {
        require(this.merge == null) {
            "conflicts { } already registered a merge policy."
        }
        this.merge = merge
    }

    internal fun build(): MutationConflictRegistration<K, V> =
        MutationConflictRegistration(
            precondition = precondition,
            merge = merge,
        )
}

/** The validated conflict policy retained for the engine's conflict pipeline. */
internal class MutationConflictRegistration<K : StoreKey, V : Any>(
    internal val precondition: ((MutationPreconditionCandidate<K, V>) -> StoreMeta?)?,
    internal val merge: (
        (
            base: MutationPresence<V>,
            mine: MutationPresence<V>,
            theirs: MutationPresence<V>,
        ) -> MutationConflictResolution<V>
    )?,
)

/**
 * One immutable snapshot of a [MutationStoreBuilder]'s retained configuration.
 *
 * [sourceOfTruth] and [bookkeeper] are the exact selected-or-default instances forwarded to both
 * the delegated core Store and the mutation engine. [applyCoreConfiguration] replays every
 * configured core door onto the factory's core builder; unset optional doors are not replayed, so
 * core keeps its own defaults for them, while persistence and bookkeeping always install the
 * retained instances. [wallClock] is additionally read by the factory so the engine's enqueue
 * and failure stamps share the configured clock; when unset, core keeps its internal default and
 * the engine uses the mutations-owned system clock.
 */
internal class MutationStoreConfiguration<K : StoreKey, V : Any>(
    private val installFetcher: StoreBuilder<K, V>.() -> Unit,
    internal val sourceOfTruth: SourceOfTruth<K, V>,
    internal val bookkeeper: Bookkeeper,
    private val telemetry: StoreTelemetry?,
    internal val wallClock: WallClock?,
    private val freshnessValidator: FreshnessValidator?,
    private val maxIdleKeys: Int?,
    internal val conflicts: MutationConflictRegistration<K, V>?,
    internal val journalStorage: MutationJournalStorage,
) {
    internal fun applyCoreConfiguration(builder: StoreBuilder<K, V>) {
        builder.installFetcher()
        builder.persistence(sourceOfTruth)
        builder.bookkeeper(bookkeeper)
        telemetry?.let(builder::telemetry)
        wallClock?.let(builder::wallClock)
        freshnessValidator?.let(builder::freshnessValidator)
        maxIdleKeys?.let(builder::maxIdleKeys)
    }
}
