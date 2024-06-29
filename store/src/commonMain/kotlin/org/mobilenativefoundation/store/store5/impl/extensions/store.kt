package org.mobilenativefoundation.store.store5.impl.extensions

import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.Bookkeeper
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.Updater
import org.mobilenativefoundation.store.store5.impl.RealMutableStore
import org.mobilenativefoundation.store.store5.impl.RealStore

/**
 * Helper factory that will return data for [key] if it is cached otherwise will return
 * fresh/network data (updating your caches)
 */
suspend fun <Key : Any, Output : Any> Store<Key, Output>.get(key: Key) =
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
suspend fun <Key : Any, Output : Any> Store<Key, Output>.fresh(key: Key) =
    stream(StoreReadRequest.fresh(key))
        .filterNot { it is StoreReadResponse.Loading || it is StoreReadResponse.NoNewData }
        .first()
        .requireData()

@OptIn(ExperimentalStoreApi::class)
@Suppress("UNCHECKED_CAST")
fun <Key : Any, Network : Any, Output : Any, Local : Any, Response : Any> Store<Key, Output>.asMutableStore(
    updater: Updater<Key, Output, Response>,
    bookkeeper: Bookkeeper<Key>?,
): MutableStore<Key, Output> {
    val delegate =
        this as? RealStore<Key, Network, Output, Local>
            ?: throw Exception("MutableStore requires Store to be built using StoreBuilder")

    return RealMutableStore(
        delegate = delegate,
        updater = updater,
        bookkeeper = bookkeeper,
    )
}

@OptIn(ExperimentalStoreApi::class)
suspend fun <Key : Any, Output : Any, Response : Any> MutableStore<Key, Output>.get(key: Key) =
    stream<Response>(StoreReadRequest.cached(key, refresh = false))
        .filterNot { it is StoreReadResponse.Loading || it is StoreReadResponse.NoNewData }
        .first()
        .requireData()

@OptIn(ExperimentalStoreApi::class)
suspend fun <Key : Any, Output : Any, Response : Any> MutableStore<Key, Output>.fresh(key: Key) =
    stream<Response>(StoreReadRequest.fresh(key))
        .filterNot { it is StoreReadResponse.Loading || it is StoreReadResponse.NoNewData }
        .first()
        .requireData()
