package com.dropbox.android.external.store4

import kotlin.time.Duration
import kotlin.time.ExperimentalTime

fun interface Weigher<in K : Any, in V : Any> {
    /**
     * Returns the weight of a cache entry. There is no unit for entry weights; rather they are simply
     * relative to each other.
     *
     * @return the weight of the entry; must be non-negative
     */
    fun weigh(key: K, value: V): Int
}

internal object OneWeigher : Weigher<Any, Any> {
    override fun weigh(key: Any, value: Any): Int = 1
}

/**
 * MemoryPolicy holds all required info to create MemoryCache
 *
 *
 * This class is used, in order to define the appropriate parameters for the Memory [com.dropbox.android.external.cache3.Cache]
 * to be built.
 *
 *
 * MemoryPolicy is used by a [Store]
 * and defines the in-memory cache behavior.
 */
@ExperimentalTime
class MemoryPolicy<in Key : Any, in Value : Any> internal constructor(
    val expireAfterWrite: Duration,
    val expireAfterAccess: Duration,
    val maxSize: Long,
    val maxWeight: Long,
    val weigher: Weigher<Key, Value>
) {

    val isDefaultWritePolicy: Boolean = expireAfterWrite == DEFAULT_DURATION_POLICY

    val hasWritePolicy: Boolean = expireAfterWrite != DEFAULT_DURATION_POLICY

    val hasAccessPolicy: Boolean = expireAfterAccess != DEFAULT_DURATION_POLICY

    val hasMaxSize: Boolean = maxSize != DEFAULT_SIZE_POLICY

    val hasMaxWeight: Boolean = maxWeight != DEFAULT_SIZE_POLICY

    class MemoryPolicyBuilder<Key : Any, Value : Any> {
        private var expireAfterWrite = DEFAULT_DURATION_POLICY
        private var expireAfterAccess = DEFAULT_DURATION_POLICY
        private var maxSize: Long = DEFAULT_SIZE_POLICY
        private var maxWeight: Long = DEFAULT_SIZE_POLICY
        private var weigher: Weigher<Key, Value> = OneWeigher

        fun setExpireAfterWrite(expireAfterWrite: Duration): MemoryPolicyBuilder<Key, Value> =
            apply {
                check(expireAfterAccess == DEFAULT_DURATION_POLICY) {
                    "Cannot set expireAfterWrite with expireAfterAccess already set"
                }
                this.expireAfterWrite = expireAfterWrite
            }

        fun setExpireAfterAccess(expireAfterAccess: Duration): MemoryPolicyBuilder<Key, Value> =
            apply {
                check(expireAfterWrite == DEFAULT_DURATION_POLICY) {
                    "Cannot set expireAfterAccess with expireAfterWrite already set"
                }
                this.expireAfterAccess = expireAfterAccess
            }

        /**
         *  Sets the maximum number of items ([maxSize]) kept in the cache.
         *
         *  When [maxSize] is 0, entries will be discarded immediately and no values will be cached.
         *
         *  If not set, cache size will be unlimited.
         */
        fun setMaxSize(maxSize: Long): MemoryPolicyBuilder<Key, Value> = apply {
            check(maxWeight == DEFAULT_SIZE_POLICY && weigher == OneWeigher) {
                "Cannot setMaxSize when maxWeight or weigher are already set"
            }
            check(maxSize >= 0) { "maxSize cannot be negative" }
            this.maxSize = maxSize
        }

        fun setWeigherAndMaxWeight(
            weigher: Weigher<Key, Value>,
            maxWeight: Long
        ): MemoryPolicyBuilder<Key, Value> = apply {
            check(maxSize == DEFAULT_SIZE_POLICY) {
                "Cannot setWeigherAndMaxWeight when maxSize already set"
            }
            check(maxWeight >= 0) { "maxWeight cannot be negative" }
            this.weigher = weigher
            this.maxWeight = maxWeight
        }

        fun build() = MemoryPolicy<Key, Value>(
            expireAfterWrite = expireAfterWrite,
            expireAfterAccess = expireAfterAccess,
            maxSize = maxSize,
            maxWeight = maxWeight,
            weigher = weigher
        )
    }

    companion object {
        val DEFAULT_DURATION_POLICY: Duration = Duration.INFINITE
        const val DEFAULT_SIZE_POLICY: Long = -1

        fun <Key : Any, Value : Any> builder(): MemoryPolicyBuilder<Key, Value> =
            MemoryPolicyBuilder()
    }
}
