package org.mobilenativefoundation.store.store5.market.impl

import org.mobilenativefoundation.store.store5.market.NetworkUpdater
import org.mobilenativefoundation.store.store5.market.OnNetworkCompletion
import org.mobilenativefoundation.store.store5.market.definition.Converter
import org.mobilenativefoundation.store.store5.market.definition.PostRequest

internal class RealNetworkUpdater<Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any>(
    val realPost: PostRequest<Key, CommonRepresentation, NetworkWriteResponse>,
    override val converter: Converter<CommonRepresentation, NetworkWriteResponse>,
    override val responseValidator: (NetworkWriteResponse) -> Boolean,
    override val onCompletion: OnNetworkCompletion<NetworkWriteResponse>,
) : NetworkUpdater<Key, CommonRepresentation, NetworkWriteResponse> {
    override suspend fun post(key: Key, input: CommonRepresentation): NetworkWriteResponse = realPost(key, input)
}
