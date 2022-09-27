package com.dropbox.external.store5

import com.dropbox.external.store5.definition.Converter
import com.dropbox.external.store5.definition.PostRequest
import com.dropbox.external.store5.impl.RealNetworkUpdater


/**
 * Posts data to remote data source.
 * @see [MarketWriter]
 */
interface NetworkUpdater<Key : Any, Input : Any, Output : Any> {
    /**
     * Makes HTTP POST request.
     */
    suspend fun post(key: Key, input: Input): Output?

    /**
     * Represents datetime of creation. Enables [Bookkeeper] to track sync failures.
     */
    val created: Long

    /**
     * Executes on network completion.
     */
    val onCompletion: OnNetworkCompletion<Output>

    /**
     * Converts [Input] to [Output].
     */
    fun converter(input: Input): Output

    companion object {
        fun <Key : Any, Input : Any, Output : Any> by(
            post: PostRequest<Key, Input, Output>,
            created: Long,
            onCompletion: OnNetworkCompletion<Output>,
            converter: Converter<Input, Output>
        ): NetworkUpdater<Key, Input, Output> = RealNetworkUpdater(
            post, created, onCompletion, converter
        )
    }
}