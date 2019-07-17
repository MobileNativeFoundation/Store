package com.nytimes.android.external.store3.pipeline

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow

@FlowPreview
internal class PipelineFetcherStore<Key, Output>(
    private val fetcher: (Key) -> Flow<Output>
) : PipelineStore<Key, Output> {
    override suspend fun get(key: Key): Output? {
        return fetcher(key).singleOrNull()
    }

    override suspend fun fresh(key: Key): Output? {
        return fetcher(key).singleOrNull()
    }

    override fun stream(key: Key) = fetcher(key)

    override fun streamFresh(key: Key) = fetcher(key)

    override suspend fun clearMemory() {

    }

    override suspend fun clear(key: Key) {
    }
}