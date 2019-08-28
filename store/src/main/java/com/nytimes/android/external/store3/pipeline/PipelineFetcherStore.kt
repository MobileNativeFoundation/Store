package com.nytimes.android.external.store3.pipeline

import kotlinx.coroutines.flow.Flow

internal class PipelineFetcherStore<Key, Output>(
        private val fetcher: (Key) -> Flow<Output>
) : PipelineStore<Key, Output> {
    override fun stream(request: StoreRequest<Key>) = fetcher(request.key)



    override suspend fun clear(key: Key) {
    }
}