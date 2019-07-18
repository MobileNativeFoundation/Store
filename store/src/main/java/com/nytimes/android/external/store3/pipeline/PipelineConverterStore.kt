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
    override suspend fun get(key: Key): NewOutput? {
        return delegate.get(key)?.let {
            converter(key, it)
        }
    }

    override suspend fun fresh(key: Key): NewOutput? {
        return delegate.fresh(key)?.let {
            converter(key, it)
        }
    }

    override fun stream(key: Key): Flow<NewOutput> {
        return delegate.stream(key).map {
            converter(key, it)
        }
    }

    override fun streamFresh(key: Key): Flow<NewOutput> {
        return delegate.streamFresh(key).map {
            converter(key, it)
        }
    }

    override suspend fun clearMemory() {
        delegate.clearMemory()
    }

    override suspend fun clear(key: Key) {
        delegate.clearMemory()
    }
}