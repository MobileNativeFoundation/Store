package com.nytimes.android.external.store3.pipeline

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private fun <Key, In, Out> castConverter(): suspend (Key, In) -> Out {
    return { key, value ->
        @Suppress("UNCHECKED_CAST")
        key as Out
    }
}

@UseExperimental(FlowPreview::class)
class PipelineConverterStore<Key, OldOutput, NewOutput>(
        private val delegate: PipelineStore<Key, OldOutput>,
        private val converter: (suspend (Key, OldOutput) -> NewOutput) = castConverter()
) : PipelineStore<Key, NewOutput> {
    override suspend fun get(request: StoreRequest<Key>): NewOutput? {
        return delegate.get(request)?.let {
            converter(request.key, it)
        }
    }

    override fun stream(request: StoreRequest<Key>): Flow<NewOutput> {
        return delegate.stream(request).map {
            converter(request.key, it)
        }
    }

    override suspend fun clearMemory() {
        delegate.clearMemory()
    }

    override suspend fun clear(key: Key) {
        delegate.clearMemory()
    }
}