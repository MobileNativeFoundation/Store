package com.dropbox.android.external.store4

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform

// possible replacement for [Store] as an internal only representation
// if this class becomes public, should probably be named IntermediateStore to distinguish from
// Store and also clarify that it still needs to be built/open? (how do we ensure?)
interface Store<Key : Any, Output : Any> {

    /**
     * Return a flow for the given key
     */
    fun stream(request: StoreRequest<Key>): Flow<StoreResponse<Output>>

    /**
     * Purge a particular entry from memory and disk cache.
     * Persistent storage will only be cleared if a delete function was passed to
     * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
     */
    suspend fun clear(key: Key)
}

@ExperimentalCoroutinesApi
@Deprecated("Legacy")
fun <Key : Any, Output : Any> Store<Key, Output>.stream(key: Key) = stream(
    StoreRequest.skipMemory(
        key = key,
        refresh = true
    )
).transform {
    it.throwIfError()
    it.dataOrNull()?.let { output ->
        emit(output)
    }
}

suspend fun <Key : Any, Output : Any> Store<Key, Output>.get(key: Key) = stream(
    StoreRequest.cached(key, refresh = false)
).filterNot {
    it is StoreResponse.Loading
}.first().requireData()

suspend fun <Key : Any, Output : Any> Store<Key, Output>.fresh(key: Key) = stream(
    StoreRequest.fresh(key)
).filterNot {
    it is StoreResponse.Loading
}.first().requireData()
