package com.nytimes.android.external.store3.pipeline

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow

@FlowPreview
internal class PipelineFetcherStore<Key, Output>(
    private val fetcher: (Key) -> Flow<Output>
) : PipelineStore<Key, Output> {

    override fun stream(request: StoreRequest<Key>) = fetcher(request.key)

    override suspend fun clear(key: Key) {
        //nothing to clear as a PipelineFetcherStore creates the initial flow
    }
}