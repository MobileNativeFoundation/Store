package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.NetworkFetcher
import org.mobilenativefoundation.store.store5.definition.Converter
import org.mobilenativefoundation.store.store5.definition.GetRequest
import org.mobilenativefoundation.store.store5.definition.PostRequest

internal class RealNetworkFetcher<Key : Any, CommonRepresentation : Any, NetworkRepresentation : Any>(
    private val realGet: GetRequest<Key, NetworkRepresentation>,
    private val realPost: PostRequest<Key, CommonRepresentation, NetworkRepresentation>,
    private val realConverter: Converter<NetworkRepresentation, CommonRepresentation>
) : NetworkFetcher<Key, CommonRepresentation, NetworkRepresentation> {
    override suspend fun get(key: Key): NetworkRepresentation? = realGet(key)

    override suspend fun post(key: Key, input: CommonRepresentation): NetworkRepresentation? = realPost(key, input)

    override fun converter(output: NetworkRepresentation): CommonRepresentation = realConverter(output)
}
