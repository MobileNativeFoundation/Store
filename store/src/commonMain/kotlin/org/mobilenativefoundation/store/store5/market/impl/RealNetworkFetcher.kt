package org.mobilenativefoundation.store.store5.market.impl

import org.mobilenativefoundation.store.store5.market.NetworkFetcher
import org.mobilenativefoundation.store.store5.market.NetworkUpdater
import org.mobilenativefoundation.store.store5.market.definition.Converter
import org.mobilenativefoundation.store.store5.market.definition.GetRequest

internal class RealNetworkFetcher<Key : Any, CommonRepresentation : Any, NetworkRepresentation : Any, NetworkWriteResponse : Any>(
    private val realGet: GetRequest<Key, NetworkRepresentation>,
    private val updater: NetworkUpdater<Key, CommonRepresentation, NetworkWriteResponse>,
    private val realConverter: Converter<NetworkRepresentation, CommonRepresentation>
) : NetworkFetcher<Key, CommonRepresentation, NetworkRepresentation, NetworkWriteResponse>,
    NetworkUpdater<Key, CommonRepresentation, NetworkWriteResponse> by updater {
    override suspend fun get(key: Key): NetworkRepresentation? = realGet(key)

    override fun converter(networkRepresentation: NetworkRepresentation): CommonRepresentation = realConverter(networkRepresentation)
}
