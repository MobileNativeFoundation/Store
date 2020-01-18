package com.dropbox.android.external.store4

import java.util.concurrent.TimeUnit

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
class MemoryPolicy internal constructor(
    val expireAfterWrite: Long,
    val expireAfterAccess: Long,
    val expireAfterTimeUnit: TimeUnit,
    val maxSize: Long
) {

    val isDefaultWritePolicy: Boolean = expireAfterWrite == DEFAULT_POLICY

    val hasWritePolicy: Boolean = expireAfterWrite != DEFAULT_POLICY

    val hasAccessPolicy: Boolean = expireAfterAccess != DEFAULT_POLICY

    val hasMaxSize: Boolean = maxSize != DEFAULT_POLICY

    class MemoryPolicyBuilder {
        private var expireAfterWrite = DEFAULT_POLICY
        private var expireAfterAccess = DEFAULT_POLICY
        private var expireAfterTimeUnit = TimeUnit.SECONDS
        private var maxSize: Long = -1

        fun setExpireAfterWrite(expireAfterWrite: Long): MemoryPolicyBuilder = apply {
            check(expireAfterAccess == DEFAULT_POLICY) {
                "Cannot set expireAfterWrite with expireAfterAccess already set"
            }
            this.expireAfterWrite = expireAfterWrite
        }

        fun setExpireAfterAccess(expireAfterAccess: Long): MemoryPolicyBuilder = apply {
            check(expireAfterWrite == DEFAULT_POLICY) {
                "Cannot set expireAfterAccess with expireAfterWrite already set"
            }
            this.expireAfterAccess = expireAfterAccess
        }

        fun setExpireAfterTimeUnit(expireAfterTimeUnit: TimeUnit): MemoryPolicyBuilder = apply {
            this.expireAfterTimeUnit = expireAfterTimeUnit
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
            expireAfterTimeUnit = expireAfterTimeUnit,
            maxSize = maxSize
        )
    }

    companion object {
        const val DEFAULT_POLICY: Long = -1

        fun builder(): MemoryPolicyBuilder = MemoryPolicyBuilder()
    }
}
