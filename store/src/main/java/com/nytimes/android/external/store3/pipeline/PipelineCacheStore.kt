package com.nytimes.android.external.store3.pipeline

import com.com.nytimes.suspendCache.StoreCache
import com.nytimes.android.external.store3.base.impl.MemoryPolicy
import com.nytimes.android.external.store3.base.impl.StoreDefaults
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

@FlowPreview
internal class PipelineCacheStore<Key, Input, Output>(
    private val delegate: PipelineStore<Key, Input, Output>,
    memoryPolicy: MemoryPolicy? = null
) : PipelineStore<Key, Input, Output> {
    private val memCache = StoreCache.from(
        loader = { key: Key ->
            delegate.get(key)
        },
        memoryPolicy = memoryPolicy ?: StoreDefaults.memoryPolicy
    )

    override fun streamFresh(key: Key): Flow<Output> {
        return delegate.streamFresh(key)
            .onEach {
                memCache.put(key, it)
            }
    }

    override fun stream(key: Key): Flow<Output> {
        return delegate.stream(key)
            .onEach {
                memCache.put(key, it)
            }
    }

    override suspend fun get(key: Key): Output? {
        return memCache.get(key)
    }

    override suspend fun fresh(key: Key): Output? {
        return memCache.fresh(key)
    }

    override suspend fun clearMemory() {
        memCache.clearAll()
        delegate.clearMemory()
    }

    override suspend fun clear(key: Key) {
        memCache.invalidate(key)
        delegate.clear(key)
    }
}