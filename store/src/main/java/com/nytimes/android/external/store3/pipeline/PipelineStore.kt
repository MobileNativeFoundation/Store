package com.nytimes.android.external.store3.pipeline

import com.nytimes.android.external.store3.base.impl.Store
import com.nytimes.android.external.store3.pipeline.singleOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*

// taken from
// https://github.com/Kotlin/kotlinx.coroutines/blob/7699a20982c83d652150391b39567de4833d4253/kotlinx-coroutines-core/js/src/flow/internal/FlowExceptions.kt
internal class AbortFlowException :
    CancellationException("Flow was aborted, no more elements needed")

// possible replacement for [Store] as an internal only representation
// if this class becomes public, should probaly be named IntermediateStore to distingush from
// Store and also clarify that it still needs to be built/open? (how do we ensure?)
@FlowPreview
interface PipelineStore<Key, Output> {
    /**
     * Return a flow for the given key
     */
    fun stream(request: StoreRequest<Key>): Flow<Output>

    /**
     * Purge a particular entry from memory and disk cache.
     * Persister will only be cleared if they implements Clearable
     */
    suspend fun clear(key: Key)
}

@FlowPreview
fun <Key, Output> PipelineStore<Key, Output>.open(): Store<Output, Key> {
    val self = this
    return object : Store<Output, Key> {
        override suspend fun get(key: Key): Output {
            return self.stream(
                    StoreRequest.cached(key, refresh = false)
            )!!.single()
        }

        override suspend fun fresh(key: Key) = self.stream(
            StoreRequest.fresh(key)
        )!!.single()

        // We could technically implement this based on other calls but it does have a cost,
        // implementation is tricky and yigit is not sure what the use case is ¯\_(ツ)_/¯
        @FlowPreview
        override fun stream(): Flow<Pair<Key, Output>> = TODO("not supported")

        @FlowPreview
        override fun stream(key: Key) = flow {
            // mapNotNull does not make compiler happy because Output : Any is not defined, hence,
            // hand rolled map not null :/
            self.stream(
                StoreRequest.skipMemory(key, true)
            )
                .collect {
                    it?.let {
                        emit(it)
                    }
                }
        }

        override suspend fun clearMemory() {
        }

        override suspend fun clear(key: Key) = self.clear(key)

    }
}