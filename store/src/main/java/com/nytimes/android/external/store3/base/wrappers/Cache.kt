package com.nytimes.android.external.store3.base.wrappers

import com.com.nytimes.suspendCache.StoreCache
import com.nytimes.android.external.store3.base.impl.MemoryPolicy
import com.nytimes.android.external.store3.base.impl.Store
import com.nytimes.android.external.store3.base.impl.StoreDefaults
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow

fun <V, K> Store4Builder<V, K>.cache(
        memoryPolicy: MemoryPolicy? = null
): Store4Builder<V, K> = Store4Builder(MemoryCacheStore(wrappedStore, memoryPolicy))

internal class MemoryCacheStore<V, K>(
        private val wrappedStore: Store<V, K>,
        memoryPolicy: MemoryPolicy?
) : Store<V, K> {

    private val memCache = StoreCache.from(
            loader = { key: K ->
                wrappedStore.get(key)
            },
            memoryPolicy = memoryPolicy ?: StoreDefaults.memoryPolicy
    )

    override suspend fun get(key: K): V = memCache.get(key, key)

    override suspend fun fresh(key: K): V = memCache.fresh(key, key)

    @FlowPreview
    override fun stream(): Flow<Pair<K, V>> = wrappedStore.stream()

    override suspend fun clearMemory() {
        memCache.clearAll()
        wrappedStore.clearMemory()
    }

    override suspend fun clear(key: K) {
        memCache.invalidate(key)
        wrappedStore.clear(key)
    }
}