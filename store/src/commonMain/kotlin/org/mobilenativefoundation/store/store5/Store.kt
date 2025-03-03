package org.mobilenativefoundation.store.store5

/**
 * A Store is responsible for managing a particular data request.
 *
 * When you create an implementation of a Store, you provide it with a Fetcher, a function that
 * defines how data will be fetched over network.
 *
 * You can also define how your Store will cache data in-memory and on-disk. See [StoreBuilder] for
 * full configuration
 *
 * Example usage:
 *
 * val store = StoreBuilder .fromNonFlow<Pair<String, RedditConfig>, List<Post>> { (query, config)
 * -> provideRetrofit().fetchData(query, config.limit).data.children.map(::toPosts) }
 * .persister(reader = { (query, _) -> db.postDao().loadData(query) }, writer = { (query, _), posts
 * -> db.dataDAO().insertData(query, posts) }, delete = { (query, _) ->
 * db.dataDAO().clearData(query) }, deleteAll = db.postDao()::clearAllFeeds) .build()
 *
 * // single shot response viewModelScope.launch { val data = store.fresh(key) }
 *
 * // get cached data and collect future emissions as well viewModelScope.launch { val data =
 * store.cached(key, refresh=true) .collect{data.value=it } }
 */
interface Store<Key : Any, Output : Any> : Read.Stream<Key, Output>, Clear.Key<Key>, Clear.All
