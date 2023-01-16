package org.mobilenativefoundation.store.store5.util

import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreRequest
import org.mobilenativefoundation.store.store5.StoreResponse

/**
 * Helper factory that will return [StoreResponse.Data] for [key]
 * if it is cached otherwise will return fresh/network data (updating your caches)
 */
suspend fun <Key : Any, Output : Any> Store<Key, Output>.getData(key: Key) =
    stream(
        StoreRequest.cached(key, refresh = false)
    ).filterNot {
        it is StoreResponse.Loading
    }.first().let {
        StoreResponse.Data(it.requireData(), it.origin)
    }
