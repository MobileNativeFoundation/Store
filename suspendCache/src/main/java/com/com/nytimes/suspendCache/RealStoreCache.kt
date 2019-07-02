package com.com.nytimes.suspendCache

import com.nytimes.android.external.cache3.CacheBuilder
import com.nytimes.android.external.cache3.CacheLoader
import com.nytimes.android.external.cache3.Ticker
import com.nytimes.android.external.store3.base.impl.MemoryPolicy

internal class RealStoreCache<K, V>(
        private val loader: suspend (K) -> V,
        private val memoryPolicy: MemoryPolicy,
        ticker: Ticker = Ticker.systemTicker()
) : StoreCache<K, V> {
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
            .build(object : CacheLoader<K, StoreRecord<K, V>>() {
                override fun load(key: K): StoreRecord<K, V>? {
                    return StoreRecord(
                            key = key,
                            loader = loader)
                }
            })

    override suspend fun fresh(key: K): V {
        return realCache.get(key)!!.freshValue()
    }

    override suspend fun get(key: K): V {
        return realCache.get(key)!!.value()
    }

    override suspend fun put(key: K, value: V) {
        realCache.put(key, StoreRecord(
                key = key,
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
