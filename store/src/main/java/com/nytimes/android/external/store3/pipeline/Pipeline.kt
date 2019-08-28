package com.nytimes.android.external.store3.pipeline

import com.nytimes.android.external.store3.base.impl.MemoryPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

fun <Key, Output> beginPipeline(
    fetcher: (Key) -> Flow<Output>
): PipelineStore<Key, Output> {
    return PipelineFetcherStore(fetcher)
}

// this really needs a better name :/
fun <Key, Output> beginNonFlowingPipeline(
    fetcher: suspend (Key) -> Output
): PipelineStore<Key, Output> {
    return PipelineFetcherStore {
        flow {
            emit(fetcher(it))
        }
    }
}


fun <Key, OldOutput, NewOutput> PipelineStore<Key, OldOutput>.withConverter(
    converter: suspend (OldOutput) -> NewOutput
): PipelineStore<Key, NewOutput> {
    return PipelineConverterStore(this) { key, value ->
        converter(value)
    }
}

fun <Key, OldOutput, NewOutput> PipelineStore<Key, OldOutput>.withKeyConverter(
    converter: suspend (Key, OldOutput) -> NewOutput
): PipelineStore<Key, NewOutput> {
    return PipelineConverterStore(this, converter)
}

fun <Key, Output> PipelineStore<Key, Output>.withCache(
    memoryPolicy: MemoryPolicy? = null
): PipelineStore<Key, Output> {
    return PipelineCacheStore(this, memoryPolicy)
}

fun <Key, OldOutput, NewOutput> PipelineStore<Key, OldOutput>.withPersister(
    reader: (Key) -> Flow<NewOutput?>,
    writer: suspend (Key, OldOutput) -> Unit,
    delete: (suspend (Key) -> Unit)? = null
): PipelineStore<Key, NewOutput> {
    return PipelinePersister(
        fetcher = this,
        reader = reader,
        writer = writer,
        delete = delete
    )
}

@ExperimentalCoroutinesApi
fun <Key, OldOutput, NewOutput> PipelineStore<Key, OldOutput>.withNonFlowPersister(
    reader: suspend (Key) -> NewOutput?,
    writer: suspend (Key, OldOutput) -> Unit,
    delete: (suspend (Key) -> Unit)? = null
): PipelineStore<Key, NewOutput> {
    val flowable = SimplePersisterAsFlowable(
        reader = reader,
        writer = writer,
        delete = delete
    )
    return PipelinePersister(
        fetcher = this,
        reader = flowable::flowReader,
        writer = flowable::flowWriter,
        delete = if (delete == null) {
            null
        } else {
            flowable::flowDelete
        }
    )
}
