package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.store5.definition.Converter
import org.mobilenativefoundation.store.store5.definition.PostRequest
import org.mobilenativefoundation.store.store5.impl.RealNetworkUpdater

/**
 * Posts data to remote data source.
 * @see [WriteRequest]
 */
interface NetworkUpdater<Key : Any, Input : Any> {
    /**
     * Makes HTTP POST request.
     */
    suspend fun post(key: Key, input: Input): Input?

    /**
     * Executes on network completion.
     */
    val onCompletion: OnNetworkCompletion<Input>


    companion object {
        fun <Key : Any, Input : Any> by(
            post: PostRequest<Key, Input>,
            onCompletion: OnNetworkCompletion<Input>,
        ): NetworkUpdater<Key, Input> = RealNetworkUpdater(post, onCompletion)
    }
}
