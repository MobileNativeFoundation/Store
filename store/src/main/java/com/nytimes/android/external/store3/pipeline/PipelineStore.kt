package com.nytimes.android.external.store3.pipeline

import com.nytimes.android.external.store3.base.impl.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

// taken from
// https://github.com/Kotlin/kotlinx.coroutines/blob/7699a20982c83d652150391b39567de4833d4253/kotlinx-coroutines-core/js/src/flow/internal/FlowExceptions.kt
internal class AbortFlowException :
    CancellationException("Flow was aborted, no more elements needed")

// possible replacement for [Store] as an internal only representation
// if this class becomes public, should probaly be named IntermediateStore to distingush from
// Store and also clarify that it still needs to be built/open? (how do we ensure?)

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

fun <Key, Output> PipelineStore<Key, Output>.open(): Store<Output, Key> {
    val self = this
    return object : Store<Output, Key> {
        override suspend fun get(key: Key) = self.stream(
                StoreRequest.cached(key, refresh = false)
        ).first()

        override suspend fun fresh(key: Key) = self.stream(
                StoreRequest.fresh(key)
        ).first()

        // We could technically implement this based on other calls but it does have a cost,
        // implementation is tricky and yigit is not sure what the use case is ¯\_(ツ)_/¯
        override fun stream(): Flow<Pair<Key, Output>> = TODO("not supported")

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



        override suspend fun clear(key: Key) = self.clear(key)

    }
}