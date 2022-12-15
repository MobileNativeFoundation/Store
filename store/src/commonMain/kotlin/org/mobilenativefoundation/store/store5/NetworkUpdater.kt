package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.store5.definition.Converter
import org.mobilenativefoundation.store.store5.definition.PostRequest
import org.mobilenativefoundation.store.store5.impl.RealNetworkUpdater

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
