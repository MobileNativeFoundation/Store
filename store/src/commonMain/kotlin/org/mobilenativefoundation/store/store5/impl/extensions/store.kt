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
 *
 * Note: Exceptions will not be handled within this function.
 *
 * ```
 * try {
 *   store.get<Key, Common>(Key.Read.All)
 * } catch (e: Exception) {
 *   // handle exception
 * }
 * ```
 *
 * @param Key The key to get cached data for.
 * @param Output The common representation of the data.
 */
suspend fun <Key : Any, Output : Any> Store<Key, Output>.get(key: Key) =
    stream(StoreReadRequest.cached(key, refresh = false))
        .filterNot { it is StoreReadResponse.Loading || it is StoreReadResponse.NoNewData }
        .first()
        .requireData()

/**
 * Helper factory that will return fresh data for [key] while updating your caches
 *
 * If the [Fetcher] does not return any data (i.e the returned
 * [kotlinx.coroutines.flow.Flow], when collected, is empty). Then store will fall back to local
 * data **even** if you explicitly requested fresh data.
 * See https://github.com/dropbox/Store/pull/194 for context
 *
 * Note: Exceptions will not be handled within this function.
 *
 * ```
 * try {
 *   store.fresh<Key, Common>(Key.Read.All)
 * } catch (e: Exception) {
 *   // handle exception
 * }
 * ```
 *
 * @param Key The key to fetch fresh data for.
 * @param Output The common representation of the data.
 * @return The fresh data associated with the key.
 */
suspend fun <Key : Any, Output : Any> Store<Key, Output>.fresh(key: Key) =
    stream(StoreReadRequest.fresh(key))
        .filterNot { it is StoreReadResponse.Loading || it is StoreReadResponse.NoNewData }
        .first()
        .requireData()

/**
 * Extension function to convert a [Store] into a [MutableStore].
 *
 * This function allows a [Store] to be used as a [MutableStore] by providing an [Updater] and an
 * optional [Bookkeeper].
 *
 * ```
 * store.asMutableStore<Key, Dto, Common, Entity, WriteResponse>(updater, bookkeeper)
 * ```
 *
 * @param Key The type of the key used to get data.
 * @param Network The type of data returned by the fetcher
 * @param Output The common representation of the data.
 * @param Local The type of the data used by the source of truth.
 * @param Response The updater result write response type.
 * @param updater Posts data to remote data source.
 * @param bookkeeper Optionally used to track when local changes fail to sync with network.
 * @return A [MutableStore] instance.
 * @throws Exception if the [Store] is not built using [StoreBuilder].
 */
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

/**
 * Helper function that returns data for the given [key] if it is cached, otherwise it will return
 * fresh/network data (updating your caches).
 *
 * Note: Exceptions will not be handled within this function.
 *
 * ```
 * try {
 *   store.get<Key, Common, WriteResponse>(Key.Read.All)
 * } catch (e: Exception) {
 *   // handle exception
 * }
 * ```
 *
 * @param Key The key to get cached data for.
 * @param Output The common representation of the data.
 * @param Response The updater result write response type.
 * @return The data associated with the key.
 */
@OptIn(ExperimentalStoreApi::class)
suspend fun <Key : Any, Output : Any, Response : Any> MutableStore<Key, Output>.get(key: Key) =
    stream<Response>(StoreReadRequest.cached(key, refresh = false))
        .filterNot { it is StoreReadResponse.Loading || it is StoreReadResponse.NoNewData }
        .first()
        .requireData()

/**
 * Helper function that returns fresh data for the given [key] while updating your caches.
 *
 * If the [Fetcher] does not return any data (i.e., the returned [kotlinx.coroutines.flow.Flow],
 * when collected, is empty), then the store will fall back to local data even if you explicitly
 * requested fresh data.
 *
 * Note: Exceptions will not be handled within this function.
 *
 * ```
 * try {
 *   store.fresh<Key, Common, WriteResponse>(Key.Read.All)
 * } catch (e: Exception) {
 *   // handle exception
 * }
 * ```
 *
 * @param Key The key to fetch fresh data for.
 * @param Output The common representation of the data.
 * @param Response The updater result write response type.
 * @return The fresh data associated with the key.
 */
@OptIn(ExperimentalStoreApi::class)
suspend fun <Key : Any, Output : Any, Response : Any> MutableStore<Key, Output>.fresh(key: Key) =
    stream<Response>(StoreReadRequest.fresh(key))
        .filterNot { it is StoreReadResponse.Loading || it is StoreReadResponse.NoNewData }
        .first()
        .requireData()
