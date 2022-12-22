package org.mobilenativefoundation.store.store5.util

import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.impl.operators.mapIndexed

/**
 * Helper factory that will return [StoreReadResponse.Data] for [key]
 * if it is cached otherwise will return fresh/network data (updating your caches)
 */
suspend fun <Key : Any, Common : Any> Store<Key, Common>.getData(key: Key) =
    stream(
        StoreReadRequest.cached(key, refresh = false)
    ).filterNot {
        it is StoreReadResponse.Loading
    }.mapIndexed { index, value ->
        value
    }.first().let {
        StoreReadResponse.Data(it.requireData(), it.origin)
    }
