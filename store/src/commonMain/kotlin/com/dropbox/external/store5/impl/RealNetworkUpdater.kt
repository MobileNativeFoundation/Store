package com.dropbox.external.store5.impl

import com.dropbox.external.store5.NetworkUpdater
import com.dropbox.external.store5.OnNetworkCompletion
import com.dropbox.external.store5.definition.Converter
import com.dropbox.external.store5.definition.PostRequest

internal class RealNetworkUpdater<Key : Any, Input : Any>(
    val realPost: PostRequest<Key, Input>,
    override val onCompletion: OnNetworkCompletion<Input>,
) : NetworkUpdater<Key, Input> {
    override suspend fun post(key: Key, input: Input): Input? = realPost(key, input)
}
