package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.store5.definition.Converter
import org.mobilenativefoundation.store.store5.definition.GetRequest
import org.mobilenativefoundation.store.store5.impl.RealNetworkFetcher

/**
 * Gets data from remote data source.
 * @see [ReadRequest]
 */
interface NetworkFetcher<Key : Any, CommonRepresentation : Any, NetworkRepresentation : Any, NetworkWriteResponse : Any> :
    NetworkUpdater<Key, CommonRepresentation, NetworkWriteResponse> {
    /**
     * Makes HTTP GET request.
     */
    suspend fun get(key: Key): NetworkRepresentation?

    /**
     * Converts [NetworkRepresentation] to [CommonRepresentation].
     */
    fun converter(networkRepresentation: NetworkRepresentation): CommonRepresentation

    companion object {
        fun <Key : Any, CommonRepresentation : Any, NetworkRepresentation : Any, NetworkWriteResponse : Any> by(
            get: GetRequest<Key, NetworkRepresentation>,
            converter: Converter<NetworkRepresentation, CommonRepresentation>,
            updater: NetworkUpdater<Key, CommonRepresentation, NetworkWriteResponse>
        ): NetworkFetcher<Key, CommonRepresentation, NetworkRepresentation, NetworkWriteResponse> = RealNetworkFetcher(
            get, updater, converter
        )
    }
}
