package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.NetworkUpdater
import org.mobilenativefoundation.store.store5.OnNetworkCompletion
import org.mobilenativefoundation.store.store5.definition.Converter
import org.mobilenativefoundation.store.store5.definition.PostRequest

internal class RealNetworkUpdater<Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any>(
    val realPost: PostRequest<Key, CommonRepresentation, NetworkWriteResponse>,
    override val converter: Converter<CommonRepresentation, NetworkWriteResponse>,
    override val responseValidator: (NetworkWriteResponse) -> Boolean,
    override val onCompletion: OnNetworkCompletion<NetworkWriteResponse>,
) : NetworkUpdater<Key, CommonRepresentation, NetworkWriteResponse> {
    override suspend fun post(key: Key, input: CommonRepresentation): NetworkWriteResponse = realPost(key, input)
}
