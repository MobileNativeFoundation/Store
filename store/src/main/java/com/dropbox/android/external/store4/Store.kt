package com.dropbox.android.external.store4

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * A Store is responsible for managing a particular data request.
 *
 * When you create an implementation of a Store, you provide it with a Fetcher, a function that defines how data will be fetched over network.
 *
 * You can also define how your Store will cache data in-memory and on-disk. See [StoreBuilder] for full configuration
 *
 * Example usage:
 *
 * val store = StoreBuilder
 *  .fromNonFlow<Pair<String, RedditConfig>, List<Post>> { (query, config) ->
 *    provideRetrofit().fetchData(query, config.limit).data.children.map(::toPosts)
 *   }
 *  .persister(reader = { (query, _) -> db.postDao().loadData(query) },
 *             writer = { (query, _), posts -> db.dataDAO().insertData(query, posts) },
 *             delete = { (query, _) -> db.dataDAO().clearData(query) },
 *             deleteAll = db.postDao()::clearAllFeeds)
 *  .build()
 *
 *  // single shot response
 *  viewModelScope.launch {
 *      val data = store.fresh(key)
 *  }
 *
 *  // get cached data and collect future emissions as well
 *  viewModelScope.launch {
 *    val data = store.cached(key, refresh=true)
 *                    .collect{data.value=it }
 *  }
 *
 */
interface Store<Key : Any, Output : Any> {

    /**
     * Return a flow for the given key
     * @param request - see [StoreRequest] for configurations
     */
    fun stream(request: StoreRequest<Key>): Flow<StoreResponse<Output>>

    /**
     * Purge a particular entry from memory and disk cache.
     * Persistent storage will only be cleared if a delete function was passed to
     * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
     */
    suspend fun clear(key: Key)

    /**
     * Purge all entries from memory and disk cache.
     * Persistent storage will only be cleared if a deleteAll function was passed to
     * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
     */
    @ExperimentalStoreApi
    suspend fun clearAll()
}

/**
 * Helper factory that will return data for [key] if it is cached otherwise will return
 * fresh/network data (updating your caches)
 */
suspend fun <Key : Any, Output : Any> Store<Key, Output>.get(key: Key) = stream(
    StoreRequest.cached(key, refresh = false)
).first().requireData()

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
).first().requireData()
