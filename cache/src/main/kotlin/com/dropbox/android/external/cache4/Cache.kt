package com.dropbox.android.external.cache4

import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * An in-memory key-value store with support for time-based (expiration) and size-based evictions.
 */
@ExperimentalTime
interface Cache<in Key : Any, Value : Any> {

    /**
     * Returns the value associated with [key] in this cache, or null if there is no
     * cached value for [key].
     */
    fun get(key: Key): Value?

    /**
     * Returns the value associated with [key] in this cache if exists,
     * otherwise gets the value by invoking [loader], associates the value with [key] in the cache,
     * and returns the cached value.
     *
     * Note that [loader] is executed on the caller's thread. When called from multiple threads
     * concurrently, if an unexpired value for the [key] is present by the time the [loader] returns
     * the new value, the existing value won't be replaced by the new value.
     * Instead the existing value will be returned.
     *
     * Any exceptions thrown by the [loader] will be propagated to the caller of this function.
     */
    fun get(key: Key, loader: () -> Value): Value

    /**
     * Associates [value] with [key] in this cache. If the cache previously contained a
     * value associated with [key], the old value is replaced by [value].
     */
    fun put(key: Key, value: Value)

    /**
     * Discards any cached value for key [key].
     */
    fun invalidate(key: Key)

    /**
     * Discards all entries in the cache.
     */
    fun invalidateAll()

    /**
     * Main entry point for creating a [Cache].
     */
    interface Builder {

        /**
         * Specifies that each entry should be automatically removed from the cache once a fixed duration
         * has elapsed after the entry's creation or the most recent replacement of its value.
         *
         * When [duration] is zero, the cache's max size will be set to 0
         * meaning no values will be cached.
         */
        fun expireAfterWrite(duration: Duration): Builder

        /**
         * Specifies that each entry should be automatically removed from the cache once a fixed duration
         * has elapsed after the entry's creation, the most recent replacement of its value, or its last
         * access.
         *
         * When [duration] is zero, the cache's max size will be set to 0
         * meaning no values will be cached.
         */
        fun expireAfterAccess(duration: Duration): Builder

        /**
         * Specifies the maximum number of entries the cache may contain.
         * Cache eviction policy is based on LRU - i.e. least recently accessed entries get evicted first.
         *
         * When [size] is 0, entries will be discarded immediately and no values will be cached.
         *
         * If not set, cache size will be unlimited.
         */
        fun maximumCacheSize(size: Long): Builder

        /**
         * Guides the allowed concurrent update operations. This is used as a hint for internal sizing,
         * actual concurrency will vary.
         *
         * If not set, default concurrency level is 16.
         */
        fun concurrencyLevel(concurrencyLevel: Int): Builder

        /**
         * Specifies [Clock] for this cache.
         *
         * This is useful for controlling time in tests
         * where a fake [Clock] implementation can be provided.
         *
         * A [SystemClock] will be used if not specified.
         */
        fun clock(clock: Clock): Builder

        /**
         * Builds a new instance of [Cache] with the specified configurations.
         */
        fun <K : Any, V : Any> build(): Cache<K, V>

        companion object {

            /**
             * Returns a new [Cache.Builder] instance.
             */
            fun newBuilder(): Builder = CacheBuilderImpl()
        }
    }
}

/**
 * A default implementation of [Cache.Builder].
 */
@ExperimentalTime
internal class CacheBuilderImpl : Cache.Builder {

    private var expireAfterWriteDuration = Duration.INFINITE
    private var expireAfterAccessDuration = Duration.INFINITE
    private var maxSize = UNSET_LONG
    private var concurrencyLevel = DEFAULT_CONCURRENCY_LEVEL
    private var clock: Clock? = null

    @ExperimentalTime
    override fun expireAfterWrite(duration: Duration): CacheBuilderImpl = apply {
        require(duration.isPositive()) {
            "expireAfterWrite duration must be positive"
        }
        this.expireAfterWriteDuration = duration
    }

    @ExperimentalTime
    override fun expireAfterAccess(duration: Duration): CacheBuilderImpl = apply {
        require(duration.isPositive()) {
            "expireAfterAccess duration must be positive"
        }
        this.expireAfterAccessDuration = duration
    }

    override fun maximumCacheSize(size: Long): CacheBuilderImpl = apply {
        require(size >= 0) {
            "maximum size must not be negative"
        }
        this.maxSize = size
    }

    override fun concurrencyLevel(concurrencyLevel: Int): Cache.Builder = apply {
        require(concurrencyLevel > 0) {
            "concurrency level must be positive"
        }
        this.concurrencyLevel = concurrencyLevel
    }

    override fun clock(clock: Clock): CacheBuilderImpl = apply {
        this.clock = clock
    }

    @ExperimentalTime
    override fun <K : Any, V : Any> build(): Cache<K, V> {
        return RealCache(
            expireAfterWriteDuration,
            expireAfterAccessDuration,
            maxSize,
            concurrencyLevel,
            clock ?: SystemClock
        )
    }

    companion object {
        internal const val UNSET_LONG: Long = -1
        internal const val DEFAULT_CONCURRENCY_LEVEL = 16
    }
}
