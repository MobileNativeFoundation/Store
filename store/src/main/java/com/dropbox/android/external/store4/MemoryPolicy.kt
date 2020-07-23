package com.dropbox.android.external.store4

import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration

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
class MemoryPolicy internal constructor(
    val expireAfterWrite: Duration,
    val expireAfterAccess: Duration,
    val maxSize: Long
) {

    val isDefaultWritePolicy: Boolean = expireAfterWrite == DEFAULT_DURATION_POLICY

    val hasWritePolicy: Boolean = expireAfterWrite != DEFAULT_DURATION_POLICY

    val hasAccessPolicy: Boolean = expireAfterAccess != DEFAULT_DURATION_POLICY

    val hasMaxSize: Boolean = maxSize != DEFAULT_SIZE_POLICY

    class MemoryPolicyBuilder {
        private var expireAfterWrite = DEFAULT_DURATION_POLICY
        private var expireAfterAccess = DEFAULT_DURATION_POLICY
        private var maxSize: Long = -1

        fun setExpireAfterWrite(expireAfterWrite: Duration): MemoryPolicyBuilder = apply {
            check(expireAfterAccess == DEFAULT_DURATION_POLICY) {
                "Cannot set expireAfterWrite with expireAfterAccess already set"
            }
            this.expireAfterWrite = expireAfterWrite
        }

        fun setExpireAfterAccess(expireAfterAccess: Duration): MemoryPolicyBuilder = apply {
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
        fun setMemorySize(maxSize: Long): MemoryPolicyBuilder = apply {
            this.maxSize = maxSize
        }

        fun build() = MemoryPolicy(
            expireAfterWrite = expireAfterWrite,
            expireAfterAccess = expireAfterAccess,
            maxSize = maxSize
        )
    }

    companion object {
        // Ideally this would be set to Duration.INFINITE, but this breaks consumers compiling with
        // Kotlin 1.4-M3 (not sure why).
        // TODO: revert back to using Duration.INFINITE once Store is compiled with Kotlin 1.4
        val DEFAULT_DURATION_POLICY: Duration = Double.POSITIVE_INFINITY.toDuration(TimeUnit.SECONDS)
        const val DEFAULT_SIZE_POLICY: Long = -1

        fun builder(): MemoryPolicyBuilder = MemoryPolicyBuilder()
    }
}
