package com.com.nytimes.suspendCache

import com.nytimes.android.external.store3.base.impl.MemoryPolicy

typealias Loader<K, V> = suspend (K) -> V

/**
 * Cache definition used by Store internally.
 */
// TODO this API is sub-optimal because it supports both Store & Pipeline where requirements
// mismatch. We can simplify it after we get rid of one of them.
interface StoreCache<K, V, Request> {
    suspend fun get(key: K, request : Request): V
    suspend fun fresh(key: K, request : Request): V
    suspend fun put(key: K, value: V)
    suspend fun invalidate(key: K)
    suspend fun clearAll()
    suspend fun getIfPresent(key: K): V?

    companion object {
        fun <K, V> from(
                loader: suspend (K) -> V,
                memoryPolicy: MemoryPolicy
        ): StoreCache<K, V, K> {
            return RealStoreCache(
                    loader = loader,
                    memoryPolicy = memoryPolicy
            )
        }

        // TODO rename to from after cleanup. Has a different name to avoid conflict w/ the old
        //  `from`.
        fun <K, V, Request> fromRequest(
                loader: suspend (Request) -> V,
                memoryPolicy: MemoryPolicy
        ): StoreCache<K, V, Request> {
            return RealStoreCache(
                    loader = loader,
                    memoryPolicy = memoryPolicy
            )
        }
    }
}
