package com.dropbox.android.external.cache4

/**
 * A cache entry holds the [key] and [value] pair,
 * along with the metadata needed to perform cache expiration and eviction.
 */
internal data class CacheEntry<Key, Value>(
    val key: Key,
    @Volatile var value: Value,
    @Volatile var accessTimeNanos: Long = Long.MAX_VALUE,
    @Volatile var writeTimeNanos: Long = Long.MAX_VALUE
)
