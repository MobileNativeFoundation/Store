package com.nytimes.android.external.store3.pipeline

import com.nytimes.android.external.store3.base.impl.MemoryPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow

// used when there is no input
class NoInput

@FlowPreview
fun <Key, Output> beginPipeline(
    fetcher: (Key) -> Flow<Output>
): PipelineStore<Key, NoInput, Output> {
    return PipelineFetcherStore(fetcher)
}


@FlowPreview
fun <Key, Input, OldOutput, NewOutput> PipelineStore<Key, Input, OldOutput>.withConverter(
    converter: suspend (OldOutput) -> NewOutput
): PipelineStore<Key, Input, NewOutput> {
    return PipelineConverterStore(this) { key, value ->
        converter(value)
    }
}

@FlowPreview
fun <Key, Input, OldOutput, NewOutput> PipelineStore<Key, Input, OldOutput>.withKeyConverter(
    converter: suspend (Key, OldOutput) -> NewOutput
): PipelineStore<Key, Input, NewOutput> {
    return PipelineConverterStore(this, converter)
}

@FlowPreview
fun <Key, Input, Output> PipelineStore<Key, Input, Output>.withCache(
    memoryPolicy: MemoryPolicy? = null
): PipelineStore<Key, Input, Output> {
    return PipelineCacheStore(this, memoryPolicy)
}

@FlowPreview
fun <Key, OldInput, OldOutput, NewOutput> PipelineStore<Key, OldInput, OldOutput>.withPersister(
    reader: (Key) -> Flow<NewOutput>,
    writer: suspend (Key, OldOutput) -> Unit,
    delete: (suspend (Key) -> Unit)? = null
): PipelineStore<Key, OldOutput, NewOutput> {
    return PipelinePersister(
        fetcher = this,
        reader = reader,
        writer = writer,
        delete = delete
    )
}

@ExperimentalCoroutinesApi
@FlowPreview
fun <Key, OldInput, OldOutput, NewOutput> PipelineStore<Key, OldInput, OldOutput>.withNonFlowPersister(
    reader: suspend (Key) -> NewOutput?,
    writer: suspend (Key, OldOutput) -> Unit,
    delete: (suspend (Key) -> Unit)? = null
): PipelineStore<Key, OldOutput, NewOutput> {
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
