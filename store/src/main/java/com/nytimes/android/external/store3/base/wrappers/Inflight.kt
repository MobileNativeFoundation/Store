package com.nytimes.android.external.store3.base.wrappers

import com.com.nytimes.suspendCache.StoreCache
import com.nytimes.android.external.store3.base.impl.MemoryPolicy
import com.nytimes.android.external.store3.base.impl.Store
import com.nytimes.android.external.store3.base.impl.StoreDefaults
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow

internal class InflightStore<V, K>(
        private val wrappedStore: Store<V, K>,
        memoryPolicy: MemoryPolicy?
) : Store<V, K> {
    private val inFlightRequests = StoreCache.from(
            loader = { key: K ->
                wrappedStore.get(key)
            },
            memoryPolicy = memoryPolicy ?: StoreDefaults.memoryPolicy
    )

    override suspend fun get(key: K): V {
        return try {
            inFlightRequests.get(key)
        } finally {
            inFlightRequests.invalidate(key)
        }
    }

    override suspend fun fresh(key: K): V {
        return try {
            inFlightRequests.fresh(key)
        } finally {
            inFlightRequests.invalidate(key)
        }
    }

    @FlowPreview
    override fun stream(): Flow<Pair<K, V>> = wrappedStore.stream()

    override suspend fun clearMemory() {
        inFlightRequests.clearAll()
        wrappedStore.clearMemory()
    }

    override suspend fun clear(key: K) {
        inFlightRequests.invalidate(key)
        wrappedStore.clear(key)
    }
}