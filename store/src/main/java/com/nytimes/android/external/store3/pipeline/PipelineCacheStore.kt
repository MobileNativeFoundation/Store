package com.nytimes.android.external.store3.pipeline

import com.com.nytimes.suspendCache.StoreCache
import com.nytimes.android.external.store3.base.impl.MemoryPolicy
import com.nytimes.android.external.store3.base.impl.StoreDefaults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

internal class PipelineCacheStore<Key, Output>(
    private val delegate: PipelineStore<Key, Output>,
    memoryPolicy: MemoryPolicy? = null
) : PipelineStore<Key, Output> {
    private val memCache = StoreCache.fromRequest<Key, Output?, StoreRequest<Key>>(
        loader = {
            TODO(
                """
                    This should've never been called. We don't need this anymore, should remove
                    loader after we clean old Store ?
                """.trimIndent()
            )
        },
        memoryPolicy = memoryPolicy ?: StoreDefaults.memoryPolicy
    )

    override fun stream(request: StoreRequest<Key>): Flow<StoreResponse<Output>> {
        @Suppress("RemoveExplicitTypeArguments")
        return flow<StoreResponse<Output>> {
            if (!request.shouldSkipCache(CacheType.MEMORY)) {
                val cached = memCache.getIfPresent(request.key)
                cached?.let {
                    emit(
                        StoreResponse.Data(
                            value = it,
                            origin = ResponseOrigin.Cache
                        )
                    )
                    if (!request.refresh) {
                        return@flow
                    }
                }
            }
            emitAll(delegate.stream(request).onEach {
                it.dataOrNull()?.let {
                    memCache.put(request.key, it)
                }
            })
        }
    }

    override suspend fun clear(key: Key) {
        memCache.invalidate(key)
        delegate.clear(key)
    }
}