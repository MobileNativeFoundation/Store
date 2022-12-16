package org.mobilenativefoundation.store.store5.market

import org.mobilenativefoundation.store.store5.market.definition.Converter
import org.mobilenativefoundation.store.store5.market.definition.PostRequest
import org.mobilenativefoundation.store.store5.market.impl.RealNetworkUpdater

/**
 * Posts data to remote data source.
 * @see [WriteRequest]
 */
interface NetworkUpdater<Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> {
    /**
     * Makes HTTP POST request.
     */
    suspend fun post(key: Key, input: CommonRepresentation): NetworkWriteResponse

    /**
     * Executes on network completion.
     */
    val onCompletion: OnNetworkCompletion<NetworkWriteResponse>

    val responseValidator: (NetworkWriteResponse) -> Boolean

    val converter: Converter<CommonRepresentation, NetworkWriteResponse>

    companion object {
        fun <Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> by(
            post: PostRequest<Key, CommonRepresentation, NetworkWriteResponse>,
            converter: Converter<CommonRepresentation, NetworkWriteResponse>,
            responseValidator: (NetworkWriteResponse) -> Boolean,
            onCompletion: OnNetworkCompletion<NetworkWriteResponse>,
        ): NetworkUpdater<Key, CommonRepresentation, NetworkWriteResponse> = RealNetworkUpdater(
            post, converter, responseValidator, onCompletion
        )
    }
}
