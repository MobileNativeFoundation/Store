package com.dropbox.external.store5.impl

import com.dropbox.external.store5.NetworkFetcher
import com.dropbox.external.store5.definition.Converter
import com.dropbox.external.store5.definition.GetRequest
import com.dropbox.external.store5.definition.PostRequest

internal class RealNetworkFetcher<Key : Any, Input : Any, Output : Any>(
    private val realGet: GetRequest<Key, Output>,
    private val realPost: PostRequest<Key, Input, Output>,
    private val realConverter: Converter<Output, Input>
) : NetworkFetcher<Key, Input, Output> {
    override suspend fun get(key: Key): Output? = realGet(key)

    override suspend fun post(key: Key, input: Input): Output? = realPost(key, input)

    override fun converter(output: Output): Input = realConverter(output)

}