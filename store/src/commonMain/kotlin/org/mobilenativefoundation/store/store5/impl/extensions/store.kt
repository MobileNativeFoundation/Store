package org.mobilenativefoundation.store.store5.impl.extensions

import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse

/**
 * Helper factory that will return data for [key] if it is cached otherwise will return
 * fresh/network data (updating your caches)
 */
suspend fun <Key : Any, CommonRepresentation : Any> Store<Key, CommonRepresentation>.get(key: Key) =
    stream(StoreReadRequest.cached(key, refresh = false))
        .filterNot { it is StoreReadResponse.Loading || it is StoreReadResponse.NoNewData }
        .first()
        .requireData()

/**
 * Helper factory that will return fresh data for [key] while updating your caches
 *
 * Note: If the [Fetcher] does not return any data (i.e the returned
 * [kotlinx.coroutines.Flow], when collected, is empty). Then store will fall back to local
 * data **even** if you explicitly requested fresh data.
 * See https://github.com/dropbox/Store/pull/194 for context
 */
suspend fun <Key : Any, CommonRepresentation : Any> Store<Key, CommonRepresentation>.fresh(key: Key) =
    stream(StoreReadRequest.fresh(key))
        .filterNot { it is StoreReadResponse.Loading || it is StoreReadResponse.NoNewData }
        .first()
        .requireData()
