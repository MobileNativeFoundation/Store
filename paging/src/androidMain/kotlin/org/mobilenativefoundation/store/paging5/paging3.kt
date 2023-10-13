package org.mobilenativefoundation.store.paging5

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.impl.extensions.get


/**
 * Converts the given [Store] into a [PagingSource] suitable for use with Paging3.
 *
 * @param keyProvider Provides methods to determine refresh and next keys for pagination.
 *
 * @return A [PagingSource] which can be used with a [Pager].
 */
inline fun <reified Id : Any,
        reified Key : StoreKey<Id>,
        reified Value : Identifiable.Single<Id>>
        Store<Key, Identifiable.Collection<Id>>.asPagingSource(
    keyProvider: PaginationKeyProvider<Id, Key, Value, Identifiable.Collection<Id>>,
): PagingSource<Key, Value> {
    return object : PagingSource<Key, Value>() {
        override fun getRefreshKey(state: PagingState<Key, Value>): Key? =
            keyProvider.determineRefreshKey(state)

        override suspend fun load(params: LoadParams<Key>): LoadResult<Key, Value> {
            return try {
                val key = params.key ?: return LoadResult.Invalid()
                val data = get(key)

                val items = data.items.mapNotNull { it as? Value }
                if (items.size == data.items.size) {
                    LoadResult.Page(data = items, prevKey = key, nextKey = keyProvider.determineNextKey(key, data))
                } else {
                    LoadResult.Error(ClassCastException("Expected items of type PagingOutput"))
                }
            } catch (error: Exception) {
                LoadResult.Error(error)
            }
        }

    }
}

/**
 * Interface to provide pagination keys for the [Pager].
 */
interface PaginationKeyProvider<Id : Any, Key : StoreKey<Id>, Value : Identifiable.Single<Id>, StoreOutput : Identifiable.Collection<Id>> {
    fun determineRefreshKey(state: PagingState<Key, Value>): Key?
    fun determineNextKey(key: Key, output: StoreOutput): Key?
}


/**
 * Creates a [Pager] backed by the given [Store].
 *
 * @param config Configuration for the paging behavior.
 * @param initialKey Initial key to be used when loading data for the first time.
 * @param keyProvider Provides methods to determine refresh and next keys for pagination.
 *
 * @return A [Pager] which can be used to paginate through the data.
 */
inline fun <reified Id : Any, reified Key : StoreKey<Id>, reified Value : Identifiable.Single<Id>>
        Store<Key, Identifiable.Collection<Id>>.pager(
    config: PagingConfig,
    initialKey: Key? = null,
    keyProvider: PaginationKeyProvider<Id, Key, Value, Identifiable.Collection<Id>>
): Pager<Key, Value> {
    return Pager(
        config = config,
        initialKey = initialKey,
        pagingSourceFactory = { this.asPagingSource(keyProvider) }
    )
}


/**
 * Creates a [Pager] backed by the given [Store].
 *
 * @param config Configuration for the paging behavior.
 * @param initialKey Initial key to be used when loading data for the first time.
 * @param keyProvider Provides methods to determine refresh and next keys for pagination.
 *
 * @return A [Pager] which can be used to paginate through the data.
 */
inline fun <reified Key : StoreKey<String>, reified Value : Identifiable.Single<String>>
        Store<Key, Identifiable.Collection<String>>.pager(
    config: PagingConfig,
    initialKey: Key? = null,
    keyProvider: PaginationKeyProvider<String, Key, Value, Identifiable.Collection<String>>
): Pager<Key, Value> = this.pager<String, Key, Value>(config, initialKey, keyProvider)