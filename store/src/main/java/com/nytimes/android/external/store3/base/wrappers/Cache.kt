package com.nytimes.android.external.store3.base.wrappers

import com.nytimes.android.external.cache3.CacheLoader
import com.nytimes.android.external.cache3.LoadingCache
import com.nytimes.android.external.store3.base.impl.CacheFactory
import com.nytimes.android.external.store3.base.impl.MemoryPolicy
import com.nytimes.android.external.store3.base.impl.Store
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

fun <V, K> Store4Builder<V, K>.cache(
        memoryPolicy: MemoryPolicy? = null
): Store4Builder<V, K> = Store4Builder(MemoryCacheStore(wrappedStore, memoryPolicy))

internal class MemoryCacheStore<V, K>(
        private val wrappedStore: Store<V, K>,
        memoryPolicy: MemoryPolicy?
) : Store<V, K> {

    //TODO this could be a Cache<K, V> but it uses a deferred because memCache.get doesn't support suspending methods
    private val memCache: LoadingCache<K, Deferred<V>> = CacheFactory.createCache(memoryPolicy,
            object : CacheLoader<K, Deferred<V>>() {
                override fun load(key: K): Deferred<V>? =
                        memoryScope.async {
                            wrappedStore.get(key)
                        }

            })
    private val memoryScope = CoroutineScope(SupervisorJob())


    override suspend fun get(key: K): V {
        return try {
            memCache.get(key)!!.await()
        } catch (e: Exception) {
            memCache.invalidate(key)
            throw e
        }
    }

    override suspend fun fresh(key: K): V {
        val value = wrappedStore.fresh(key)
        memCache.put(key, memoryScope.async { value })
        return value
    }

    @FlowPreview
    override fun stream(): Flow<Pair<K, V>> = wrappedStore.stream()

    override fun clearMemory() {
        memCache.invalidateAll()
        wrappedStore.clearMemory()
    }

    override fun clear(key: K) {
        memCache.invalidate(key)
        wrappedStore.clear(key)
    }
}