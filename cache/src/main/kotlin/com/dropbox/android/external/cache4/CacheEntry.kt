package com.dropbox.android.external.cache4

/**
 * A cache entry holds the [key] and [value] pair,
 * along with the metadata needed to perform cache expiration and eviction.
 *
 * A cache entry can be reused by updating [value], [accessTimeNanos], or [writeTimeNanos],
 * as this allows us to avoid creating new instance of [CacheEntry] on every access and write.
 *
 * Note that we assume cache entries are stored in thread-safe collections such as ConcurrentHashMap.
 *
 */
internal data class CacheEntry<Key, Value>(
    val key: Key,
    var value: Value,
    var accessTimeNanos: Long = Long.MAX_VALUE,
    var writeTimeNanos: Long = Long.MAX_VALUE
)
