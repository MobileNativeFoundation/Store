package com.dropbox.android.external.cache4

import java.util.concurrent.TimeUnit

/**
 * An in-memory key-value store with support for time-based (expiration) and size-based evictions.
 */
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
     * When this is called from multiple threads concurrently, if an unexpired value for the [key]
     * is present by the time the [loader] returns the new value, the existing value won't be
     * replaced by the new value. Instead the existing value will be returned.
     *
     * Note that [loader] is executed on the caller's thread.
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
        fun expireAfterWrite(duration: Long, unit: TimeUnit): Builder

        /**
         * Specifies that each entry should be automatically removed from the cache once a fixed duration
         * has elapsed after the entry's creation, the most recent replacement of its value, or its last
         * access.
         *
         * When [duration] is zero, the cache's max size will be set to 0
         * meaning no values will be cached.
         */
        fun expireAfterAccess(duration: Long, unit: TimeUnit): Builder

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
internal class CacheBuilderImpl : Cache.Builder {

    private var expireAfterWriteNanos = UNSET_LONG
    private var expireAfterAccessNanos = UNSET_LONG
    private var maxSize = UNSET_LONG
    private var clock: Clock? = null

    override fun expireAfterWrite(duration: Long, unit: TimeUnit): CacheBuilderImpl = apply {
        require(duration >= 0) {
            "expireAfterWrite duration cannot be negative: $duration $unit"
        }
        this.expireAfterWriteNanos = unit.toNanos(duration)
    }

    override fun expireAfterAccess(duration: Long, unit: TimeUnit): CacheBuilderImpl = apply {
        require(duration >= 0) {
            "expireAfterAccess duration cannot be negative: $duration $unit"
        }
        this.expireAfterAccessNanos = unit.toNanos(duration)
    }

    override fun maximumCacheSize(size: Long): CacheBuilderImpl = apply {
        require(size >= 0) {
            "maximum size must not be negative"
        }
        this.maxSize = size
    }

    override fun clock(clock: Clock): CacheBuilderImpl = apply {
        this.clock = clock
    }

    override fun <K : Any, V : Any> build(): Cache<K, V> {
        val effectiveExpireAfterWrite = if (expireAfterWriteNanos == UNSET_LONG) {
            DEFAULT_EXPIRATION_NANOS
        } else {
            expireAfterWriteNanos
        }

        val effectiveExpireAfterAccess = if (expireAfterAccessNanos == UNSET_LONG) {
            DEFAULT_EXPIRATION_NANOS
        } else {
            expireAfterAccessNanos
        }

        val effectiveMaxSize = if (expireAfterWriteNanos == 0L || expireAfterAccessNanos == 0L) {
            0
        } else {
            maxSize
        }

        val effectiveClock = clock ?: SystemClock

        return RealCache(
            effectiveExpireAfterWrite,
            effectiveExpireAfterAccess,
            effectiveMaxSize,
            effectiveClock
        )
    }

    companion object {
        internal const val UNSET_LONG: Long = -1
        internal const val DEFAULT_EXPIRATION_NANOS = 0L
    }
}
