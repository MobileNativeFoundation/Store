package com.dropbox.android.external.store4.util

import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first

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
