package com.nytimes.android.external.store4

import com.nytimes.android.external.store4.impl.PersistentNonFlowingSourceOfTruth
import com.nytimes.android.external.store4.impl.PersistentSourceOfTruth
import com.nytimes.android.external.store4.impl.RealStore
import com.nytimes.android.external.store4.impl.SourceOfTruth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

object FlowStoreBuilder {
    fun <Key, Input, Output> fromNonFlow(
            fetcher: suspend (key: Key) -> Input
    ) = Builder<Key, Input, Output> { key: Key ->
        flow {
            emit(fetcher(key))
        }
    }

    fun <Key, Input, Output> from(
            fetcher: (key: Key) -> Flow<Input>
    ) = Builder<Key, Input, Output>(fetcher)
}

class Builder<Key, Input, Output>(
        private val fetcher: (key: Key) -> Flow<Input>
) {
    private var scope: CoroutineScope? = null
    private var sourceOfTruth: SourceOfTruth<Key, Input, Output>? = null
    private var cachePolicy: MemoryPolicy? = StoreDefaults.memoryPolicy

    fun scope(scope: CoroutineScope): Builder<Key, Input, Output> {
        this.scope = scope
        return this
    }

    fun nonFlowingPersister(
            reader: suspend (Key) -> Output?,
            writer: suspend (Key, Input) -> Unit,
            delete: (suspend (Key) -> Unit)? = null
    ): Builder<Key, Input, Output> {
        sourceOfTruth = PersistentNonFlowingSourceOfTruth(
                realReader = reader,
                realWriter = writer,
                realDelete = delete
        )
        return this
    }

    fun persister(
            reader: (Key) -> Flow<Output?>,
            writer: suspend (Key, Input) -> Unit,
            delete: (suspend (Key) -> Unit)? = null
    ): Builder<Key, Input, Output> {
        sourceOfTruth = PersistentSourceOfTruth(
                realReader = reader,
                realWriter = writer,
                realDelete = delete
        )
        return this
    }

    fun sourceOfTruth(
            sourceOfTruth: SourceOfTruth<Key, Input, Output>
    ): Builder<Key, Input, Output> {
        this.sourceOfTruth = sourceOfTruth
        return this
    }

    fun cachePolicy(memoryPolicy: MemoryPolicy?): Builder<Key, Input, Output> {
        cachePolicy = memoryPolicy
        return this
    }

    fun disableCache(): Builder<Key, Input, Output> {
        cachePolicy = null
        return this
    }

    @ExperimentalCoroutinesApi
    fun build(): Store<Key, Output> {
        @Suppress("UNCHECKED_CAST")
        return RealStore(
                scope = scope ?: GlobalScope,
                sourceOfTruth = sourceOfTruth,
                fetcher = fetcher,
                memoryPolicy = cachePolicy
        )
    }
}