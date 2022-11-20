package com.dropbox.external.store5

import com.dropbox.external.store5.definition.Converter
import com.dropbox.external.store5.definition.GetRequest
import com.dropbox.external.store5.definition.PostRequest
import com.dropbox.external.store5.impl.RealNetworkFetcher

/**
 * Gets data from remote data source.
 * @see [MarketReader]
 */
interface NetworkFetcher<Key : Any, Input : Any, Output : Any> {
    /**
     * Makes HTTP GET request.
     */
    suspend fun get(key: Key): Output?

    /**
     * Makes HTTP POST request.
     */
    suspend fun post(key: Key, input: Input): Output?

    /**
     * Converts [Output] to [Input].
     */
    fun converter(output: Output): Input

    companion object {
        fun <Key : Any, Input : Any, Output : Any> by(
            get: GetRequest<Key, Output>,
            post: PostRequest<Key, Input, Output>,
            converter: Converter<Output, Input>
        ): NetworkFetcher<Key, Input, Output> = RealNetworkFetcher(
            get, post, converter
        )
    }
}