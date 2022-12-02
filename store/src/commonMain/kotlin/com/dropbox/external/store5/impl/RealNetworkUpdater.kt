package com.dropbox.external.store5.impl

import com.dropbox.external.store5.NetworkUpdater
import com.dropbox.external.store5.OnNetworkCompletion
import com.dropbox.external.store5.definition.Converter
import com.dropbox.external.store5.definition.PostRequest

internal class RealNetworkUpdater<Key : Any, Input : Any, Output : Any>(
    val realPost: PostRequest<Key, Input, Output>,
    override val onCompletion: OnNetworkCompletion<Output>,
    val realConverter: Converter<Input, Output>
) : NetworkUpdater<Key, Input, Output> {
    override suspend fun post(key: Key, input: Input): Output? = realPost(key, input)
    override fun converter(input: Input): Output = realConverter(input)
}
