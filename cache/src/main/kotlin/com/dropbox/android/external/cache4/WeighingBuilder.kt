package com.dropbox.android.external.cache4

import kotlin.time.ExperimentalTime

/**
 *
 * Builds a new instance of [Cache] with the specified configurations and a [weigher].
 * @param: weigher: Specifies the maximum weight of entries the cache may contain. Weight is determined using the
 * [Weigher] specified with [Weigher]
 *
 * <p>When [maxWeight] is zero, elements will be evicted immediately after being loaded into
 * cache. This can be useful in testing, or to disable caching temporarily without a code
 * change.
 *
 * <p>Note that weight is only used to determine whether the cache is over capacity; it has no
 * effect on selecting which entry should be evicted next.
 *
 * <p>This feature cannot be used in conjunction with {@link #maximumSize}.
 *
 * @param: maxWeight Specifies the maximum weight of entries the cache may contain. Weight is determined using the
 * {@link Weigher} specified with [Weigher]

 * <p>Note that weight is only used to determine whether the cache is over capacity; it has no
 * effect on selecting which entry should be evicted next.
 *
 * <p>This feature cannot be used in conjunction with {@link #maximumSize}.
 */
@ExperimentalTime
fun <Key:Any, Value:Any> Cache.Builder.buildWithWeigher(weigher: Weigher<Key, Value>, maxWeight:Long) : Cache<Key, Value> {
     require(maxSize == CacheBuilderImpl.UNSET_LONG) { "maximum size can not be combined with weigher" }
    return RealCache(
        expireAfterWriteDuration,
        expireAfterAccessDuration,
        maxSize,
        concurrencyLevel,
        clock ?: SystemClock,
        weigher,
        maxWeight
    )
}