package com.nytimes.android.external.store3.base.wrappers

import com.nytimes.android.external.cache3.Cache
import com.nytimes.android.external.store3.base.impl.CacheFactory
import com.nytimes.android.external.store3.base.impl.MemoryPolicy
import com.nytimes.android.external.store3.base.impl.Store
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow

internal class InflightStore<V, K>(
        private val wrappedStore: Store<V, K>,
        memoryPolicy: MemoryPolicy?
) : Store<V, K> {

    private val inFlightRequests: Cache<K, Deferred<V>> = CacheFactory.createInflighter(memoryPolicy)

    private val inFlightScope = CoroutineScope(SupervisorJob())

    override suspend fun get(key: K): V {
        return try {
            inFlightRequests
                    .get(key) { inFlightScope.async { wrappedStore.get(key) } }
                    .await()
        } finally {
            inFlightRequests.invalidate(key)
        }
    }

    override suspend fun fresh(key: K): V {
        return try {
            inFlightRequests
                    .get(key) { inFlightScope.async { wrappedStore.fresh(key) } }
                    .await()
        } finally {
            inFlightRequests.invalidate(key)
        }
    }

    @FlowPreview
    override fun stream(): Flow<Pair<K, V>> = wrappedStore.stream()

    override fun clearMemory() {
        inFlightRequests.invalidateAll()
        wrappedStore.clearMemory()
    }

    override fun clear(key: K) {
        inFlightRequests.invalidate(key)
        wrappedStore.clear(key)
    }
}