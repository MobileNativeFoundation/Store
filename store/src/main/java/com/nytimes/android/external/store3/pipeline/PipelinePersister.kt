package com.nytimes.android.external.store3.pipeline

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
@FlowPreview
class PipelinePersister<Key, Input, Output>(
        private val fetcher: PipelineStore<Key, Input>,
        private val reader: (Key) -> Flow<Output>,
        private val writer: suspend (Key, Input) -> Unit,
        private val delete: (suspend (Key) -> Unit)? = null
) : PipelineStore<Key, Output> {

    override fun stream(request: StoreRequest<Key>): Flow<Output> {
        return if (request.shouldLoadFrom(CacheType.Persistent)) {
            reader(request.key)
        } else {
            fetcher.stream(request)
                    .flatMapConcat {
                        writer(request.key, it)
                        reader(request.key)
                    }
        }
    }

    override suspend fun clear(key: Key) {
        delete?.invoke(key)
        fetcher.clear(key)
    }
}


// TODO figure out why filterNotNull does not make compiler happy
@FlowPreview
@Suppress("UNCHECKED_CAST")
private fun <T1, T2> Flow<T1>.castNonNull(): Flow<T2> {
    val self = this
    return flow {
        self.collect {
            if (it != null) {
                emit(it as T2)
            }
        }
    }
}


@FlowPreview
private fun <T, R> Flow<T>.sideCollect(
    other: Flow<R>,
    otherCollect: suspend (R) -> Unit
) = flow {
    coroutineScope {
        launch {
            other.collect {
                otherCollect(it)
            }
        }
        this@sideCollect.collect {
            emit(it)
        }
    }
}