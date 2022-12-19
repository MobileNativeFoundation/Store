package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.Flow

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
interface Store<Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> {
    /**
     * Return a flow for the given key
     * @param request - see [StoreReadRequest] for configurations
     */
    fun stream(request: StoreReadRequest<Key>): Flow<StoreReadResponse<CommonRepresentation>>

    @ExperimentalStoreApi
    fun stream(stream: Flow<StoreWriteRequest<Key, CommonRepresentation>>): Flow<StoreWriteResponse<NetworkWriteResponse>>

    @ExperimentalStoreApi
    fun write(request: StoreWriteRequest<Key, CommonRepresentation>): StoreWriteResponse<NetworkWriteResponse>

    /**
     * Purge a particular entry from memory and disk cache.
     * Persistent storage will only be cleared if a delete function was passed to
     * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
     */
    suspend fun clear(key: Key)

    /**
     * Purge all entries from memory and disk cache.
     * Persistent storage will only be cleared if a clear function was passed to
     * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
     */
    @ExperimentalStoreApi
    suspend fun clear()
}
