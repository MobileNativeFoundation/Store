package com.nytimes.android.external.store3.pipeline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*

@FlowPreview
class PipelinePersister<Key, Input, Output>(
        private val fetcher: PipelineStore<Key, Input>,
        private val reader: (Key) -> Flow<Output>,
        private val writer: suspend (Key, Input) -> Unit,
        private val delete: (suspend (Key) -> Unit)? = null
) : PipelineStore<Key, Output> {

    @ExperimentalCoroutinesApi
    override fun stream(request: StoreRequest<Key>): Flow<Output> {
        return if (request.shouldLoadFrom(CacheType.Persistent)) {
            return if (request.refresh) {
                //we need to refresh let's emit the current value and then concat the fetcher/disk flow
                reader(request.key).take(1).filter { it == null }.concatUpstreamFlow(request)
            } else {
                //load the first value from disk
                reader(request.key).take(1).flatMapConcat { firstValue ->
                    //when the first value is null we should fall through to fetcher, write the value then read again
                    if (firstValue == null) {
                        fetcher.stream(request).flatMapConcat {
                            writer(request.key, it)
                            reader(request.key)
                        }
                    } else {
                        //the first value was not null, lets emit it plus a connection to the db
                        //TODO MIKE: we are reading from the db again which is not good, ideally this should be more like a refcount/passthrough
                        flow { emit(firstValue) }.onCompletion { reader(request.key).drop(1) }
                    }
                }
            }
        } else {
            fetcher.stream(request)
                    .flatMapConcat {
                        writer(request.key, it)
                        reader(request.key)
                    }
        }
    }

    private fun Flow<Output>.concatUpstreamFlow(request: StoreRequest<Key>): Flow<Output> {
        return fetcher.stream(request)
                .flatMapConcat {
                    writer(request.key, it)
                    reader(request.key)
                }
                .startWith2(this)
    }

    override suspend fun clear(key: Key) {
        delete?.invoke(key)
        fetcher.clear(key)
    }
}


private fun <T> Flow<T>.startWith2(networkFlow: Flow<T>): Flow<T> = flow {
    this.emitAll(networkFlow)
    emitAll(this@startWith2)
}

