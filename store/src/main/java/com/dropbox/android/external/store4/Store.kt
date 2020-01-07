package com.dropbox.android.external.store4

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform

interface Store<Key, Output> {

    /**
     * Return a flow for the given key
     * @param request - see [com.dropbox.android.external.store4.StoreRequest] for configurations
     */
    fun stream(request: StoreRequest<Key>): Flow<StoreResponse<Output>>

    /**
     * Purge a particular entry from memory and disk cache.
     * Persistant storage will only be cleared if a delete function was passed to
     * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
     */
    suspend fun clear(key: Key)
}

@ExperimentalCoroutinesApi
@Deprecated("Legacy")
fun <Key, Output> Store<Key, Output>.stream(key: Key) = stream(
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

/**
 * Helper factory that will return data for [key] if it is cached otherwise will return fresh/network data (updating your caches)
 */
suspend fun <Key, Output> Store<Key, Output>.get(key: Key) = stream(
        StoreRequest.cached(key, refresh = false)
).filterNot {
    it is StoreResponse.Loading
}.first().requireData()

/**
 * Helper factory that will return fresh data for [key] while updating your caches
 */
suspend fun <Key, Output> Store<Key, Output>.fresh(key: Key) = stream(
        StoreRequest.fresh(key)
).filterNot {
    it is StoreResponse.Loading
}.first().requireData()
