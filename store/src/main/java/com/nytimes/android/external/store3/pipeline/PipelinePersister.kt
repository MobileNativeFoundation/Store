package com.nytimes.android.external.store3.pipeline

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.switchMap

@FlowPreview
class PipelinePersister<Key, Input, Output>(
        private val fetcher: PipelineStore<Key, Input>,
        private val reader: (Key) -> Flow<Output?>,
        private val writer: suspend (Key, Input) -> Unit,
        private val delete: (suspend (Key) -> Unit)? = null
) : PipelineStore<Key, Output> {
    override suspend fun get(key: Key): Output? {
        val value: Output? = reader(key).singleOrNull()
        value?.let {
            // cached value from persister
            return it
        }
        // nothing is cached, get fetcher
        val fetcherValue = fetcher.get(key)
            ?: return null // no fetch, no result
        writer(key, fetcherValue)
        return reader(key).singleOrNull()
    }

    override suspend fun fresh(key: Key): Output? {
        // nothing is cached, get fetcher
        val fetcherValue = fetcher.fresh(key)
            ?: return null // no fetch, no result TODO should we invalidate cache, probably not?
        writer(key, fetcherValue)
        return reader(key).singleOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    override fun stream(key: Key): Flow<Output> {
        // TODO we'll need refresh functionality here but StoreRequest can encapsulate that later
        return reader(key).castNonNull()
    }

    @Suppress("UNCHECKED_CAST")
    override fun streamFresh(key: Key): Flow<Output> {
        return fetcher.streamFresh(key)
            .switchMap {
                writer(key, it)
                reader(key)
            }.castNonNull()
    }

    override suspend fun clearMemory() {
        fetcher.clearMemory()
    }

    override suspend fun clear(key: Key) {
        fetcher.clear(key)
        delete?.invoke(key)
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
