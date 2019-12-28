package com.dropbox.android.external.cache4

import java.util.concurrent.TimeUnit

class CacheBuilder {

    private var expireAfterWriteNanos = UNSET_LONG
    private var expireAfterAccessNanos = UNSET_LONG
    private var maxSize = UNSET_LONG
    private var clock: Clock? = null

    /**
     * Specifies that each entry should be automatically removed from the cache once a fixed duration
     * has elapsed after the entry's creation or the most recent replacement of its value.
     *
     * When [duration] is zero, the cache's max size will be set to 0
     * meaning no values will be cached.
     */
    fun expireAfterWrite(duration: Long, unit: TimeUnit): CacheBuilder = apply {
        require(duration >= 0) {
            "expireAfterWrite duration cannot be negative: $duration $unit"
        }
        this.expireAfterWriteNanos = unit.toNanos(duration)
    }

    /**
     * Specifies that each entry should be automatically removed from the cache once a fixed duration
     * has elapsed after the entry's creation, the most recent replacement of its value, or its last
     * access.
     *
     * When [duration] is zero, the cache's max size will be set to 0
     * meaning no values will be cached.
     */
    fun expireAfterAccess(duration: Long, unit: TimeUnit): CacheBuilder = apply {
        require(duration >= 0) {
            "expireAfterAccess duration cannot be negative: $duration $unit"
        }
        this.expireAfterAccessNanos = unit.toNanos(duration)
    }

    /**
     * Specifies the maximum number of entries the cache may contain.
     * Cache eviction policy is based on LRU - i.e. least recently accessed entries get evicted first.
     *
     * When [size] is 0, entries will be discarded immediately and no values will be cached.
     *
     * If not set, cache size will be unlimited.
     */
    fun maximumCacheSize(size: Long): CacheBuilder = apply {
        require(size >= 0) {
            "maximum size must not be negative"
        }
        this.maxSize = size
    }

    /**
     * Specifies [Clock] for this cache.
     *
     * This is useful for controlling time in tests
     * where a fake [Clock] implementation can be provided.
     *
     * A [SystemClock] will be used if not specified.
     */
    fun clock(clock: Clock): CacheBuilder = apply {
        this.clock = clock
    }

    fun <K, V> build(): Cache<K, V> {
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
