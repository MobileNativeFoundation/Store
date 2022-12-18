package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first

/**
 * Helper factory that will return data for [key] if it is cached otherwise will return
 * fresh/network data (updating your caches)
 */
suspend fun <Key : Any, Output : Any> Store<Key, Output>.get(key: Key) = stream(
    StoreRequest.cached(key, refresh = false)
).filterNot {
    it is StoreResponse.Loading || it is StoreResponse.NoNewData
}.first().requireData()

/**
 * Helper factory that will return fresh data for [key] while updating your caches
 *
 * Note: If the [Fetcher] does not return any data (i.e the returned
 * [kotlinx.coroutines.Flow], when collected, is empty). Then store will fall back to local
 * data **even** if you explicitly requested fresh data.
 * See https://github.com/dropbox/Store/pull/194 for context
 */
suspend fun <Key : Any, Output : Any> Store<Key, Output>.fresh(key: Key) = stream(
    StoreRequest.fresh(key)
).filterNot {
    it is StoreResponse.Loading || it is StoreResponse.NoNewData
}.first().requireData()
