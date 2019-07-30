package com.com.nytimes.suspendCache

import com.nytimes.android.external.cache3.CacheBuilder
import com.nytimes.android.external.cache3.CacheLoader
import com.nytimes.android.external.cache3.Ticker
import com.nytimes.android.external.store3.base.impl.MemoryPolicy

internal class RealStoreCache<K, V, Request>(
        private val memoryPolicy: MemoryPolicy,
        ticker: Ticker = Ticker.systemTicker()
) : StoreCache<K, V, Request> {
    private val realCache = CacheBuilder.newBuilder()
            .ticker(ticker)
            .also {
                if (memoryPolicy.hasAccessPolicy()) {
                    it.expireAfterAccess(memoryPolicy.expireAfterAccess, memoryPolicy.expireAfterTimeUnit)
                }
                if (memoryPolicy.hasWritePolicy()) {
                    it.expireAfterWrite(memoryPolicy.expireAfterWrite, memoryPolicy.expireAfterTimeUnit)
                }
                if (memoryPolicy.hasMaxSize()) {
                    it.maximumSize(memoryPolicy.maxSize)
                }
            }
            .build(object : CacheLoader<K, StoreRecord<V, Request>>() {
                override fun load(key: K): StoreRecord<V, Request>? {
                    val loader: suspend (Request) -> V = { TODO() }
                    return StoreRecord(
                            loader = loader
                    )
                }
            })

    override suspend fun fresh(key: K, request: Request): V {
        return realCache.get(key)!!.freshValue(request)
    }

    override suspend fun get(key: K, request: Request): V {
        return realCache.get(key)!!.value(request)
    }

    override suspend fun put(key: K, value: V) {
        val loader: suspend (Request) -> V = { TODO() }

        realCache.put(key, StoreRecord(
                loader = loader,
                precomputedValue = value))
    }

    override suspend fun invalidate(key: K) {
        realCache.invalidate(key)
    }

    override suspend fun clearAll() {
        realCache.cleanUp()
    }

    override suspend fun getIfPresent(key: K): V? {
        return realCache.getIfPresent(key)?.cachedValue()
    }
}
