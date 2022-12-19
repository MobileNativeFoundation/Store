package org.mobilenativefoundation.store.store5.util

import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse

/**
 * Helper factory that will return [StoreReadResponse.Data] for [key]
 * if it is cached otherwise will return fresh/network data (updating your caches)
 */
suspend fun <Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> Store<Key, CommonRepresentation, NetworkWriteResponse>.getData(key: Key) =
    stream(
        StoreReadRequest.cached(key, refresh = false)
    ).filterNot {
        it is StoreReadResponse.Loading
    }.first().let {
        StoreReadResponse.Data(it.requireData(), it.origin)
    }
