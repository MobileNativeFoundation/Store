package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.store5.definition.Converter
import org.mobilenativefoundation.store.store5.definition.GetRequest
import org.mobilenativefoundation.store.store5.definition.PostRequest
import org.mobilenativefoundation.store.store5.impl.RealNetworkFetcher

/**
 * Gets data from remote data source.
 * @see [ReadRequest]
 */
interface NetworkFetcher<Key : Any, CommonRepresentation : Any, NetworkRepresentation : Any> {
    /**
     * Makes HTTP GET request.
     */
    suspend fun get(key: Key): NetworkRepresentation?

    /**
     * Makes HTTP POST request.
     */
    suspend fun post(key: Key, input: CommonRepresentation): NetworkRepresentation?

    /**
     * Converts [NetworkRepresentation] to [CommonRepresentation].
     */
    fun converter(output: NetworkRepresentation): CommonRepresentation

    companion object {
        fun <Key : Any, CommonRepresentation : Any, NetworkRepresentation : Any> by(
            get: GetRequest<Key, NetworkRepresentation>,
            post: PostRequest<Key, CommonRepresentation, NetworkRepresentation>,
            converter: Converter<NetworkRepresentation, CommonRepresentation>
        ): NetworkFetcher<Key, CommonRepresentation, NetworkRepresentation> = RealNetworkFetcher(
            get, post, converter
        )
    }
}
