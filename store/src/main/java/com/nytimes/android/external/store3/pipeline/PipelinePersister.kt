package com.nytimes.android.external.store3.pipeline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*

@FlowPreview
class PipelinePersister<Key, Input, Output>(
        private val fetcher: PipelineStore<Key, Input>,
        private val reader: (Key) -> Flow<Output?>,
        private val writer: suspend (Key, Input) -> Unit,
        private val delete: (suspend (Key) -> Unit)? = null
) : PipelineStore<Key, Output> {
    @ExperimentalCoroutinesApi
    @Suppress("UNCHECKED_CAST")
    override fun stream(request: StoreRequest<Key>): Flow<Output> {
        return when {
            // skipping cache, just delegate to the fetcher but update disk w/ any new data from
            // fetcher
            request.shouldSkipCache(CacheType.DISK) -> fetcher.stream(request)
                    .flatMapLatest {
                        writer(request.key, it)
                        reader(request.key)
                    }.castNonNull()
            // we want to stream from disk but also want to refresh. Immediately start fetcher
            // when flow starts.
            request.refresh -> reader(request.key)
                    .sideCollect(fetcher.stream(request)) { response: Input? ->
                        response?.let { data: Input ->
                            writer(request.key, data)
                        }
                    }.castNonNull()
            // we want to return from disk but also fetch from server if there is nothing in disk.
            // to do that, we need to see the first disk value and then decide to fetch or not.
            // in any case, we always return the Flow from reader.
            else -> reader(request.key)
                    .sideCollectMaybe(
                            otherProducer = {
                                if (it == null) {
                                    // disk returned null, create a new flow from fetcher
                                    fetcher.stream(request)
                                } else {
                                    // disk had cached value, don't trigger fetcher
                                    null
                                }
                            },
                            otherCollect = { response: Input ->
                                response?.let { data: Input ->
                                    writer(request.key, data)
                                }
                            }
                    ).castNonNull()
        }
    }


    override suspend fun clear(key: Key) {
        fetcher.clear(key)
        delete?.invoke(key)
    }
}

// TODO figure out why filterNotNull does not make compiler happy
@ExperimentalCoroutinesApi
@FlowPreview
@Suppress("UNCHECKED_CAST")
private fun <T1, T2> Flow<T1>.castNonNull() = this.transform {
    (it as? T2)?.let {
        emit(it)
    }
}
