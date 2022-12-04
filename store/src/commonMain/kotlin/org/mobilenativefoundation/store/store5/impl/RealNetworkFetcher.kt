package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.NetworkFetcher
import org.mobilenativefoundation.store.store5.definition.Converter
import org.mobilenativefoundation.store.store5.definition.GetRequest
import org.mobilenativefoundation.store.store5.definition.PostRequest

internal class RealNetworkFetcher<Key : Any, Input : Any, Output : Any>(
    private val realGet: GetRequest<Key, Output>,
    private val realPost: PostRequest<Key, Input, Output>,
    private val realConverter: Converter<Output, Input>
) : NetworkFetcher<Key, Input, Output> {
    override suspend fun get(key: Key): Output? = realGet(key)

    override suspend fun post(key: Key, input: Input): Output? = realPost(key, input)

    override fun converter(output: Output): Input = realConverter(output)
}
