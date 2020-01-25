package com.dropbox.android.external.store4.util

import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.android.external.store4.impl.DataWithOrigin
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first

/**
 * Helper factory that will return data along with the response origin for [key]
 * if it is cached otherwise will return fresh/network data (updating your caches)
 */
internal suspend fun <Key : Any, Output : Any> Store<Key, Output>.getDataWithOrigin(key: Key) =
    stream(
        StoreRequest.cached(key, refresh = false)
    ).filterNot {
        it is StoreResponse.Loading
    }.first().let {
        DataWithOrigin(it.origin, it.requireData())
    }
