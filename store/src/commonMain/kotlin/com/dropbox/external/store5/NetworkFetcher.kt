package com.dropbox.external.store5

import com.dropbox.external.store5.definition.GetRequest
import com.dropbox.external.store5.impl.RealNetworkFetcher

/**
 * Gets data from remote data source.
 * @see [ReadRequest]
 */
interface NetworkFetcher<Key : Any, Input> {
    /**
     * Makes HTTP GET request.
     */
    suspend fun get(key: Key): Input?


    companion object {
        fun <Key : Any, Input : Any> by(
            get: GetRequest<Key, Input>,
        ): NetworkFetcher<Key, Input> = RealNetworkFetcher(get)
    }
}
