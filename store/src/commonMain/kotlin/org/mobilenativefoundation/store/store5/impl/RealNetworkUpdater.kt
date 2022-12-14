package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.NetworkUpdater
import org.mobilenativefoundation.store.store5.OnNetworkCompletion
import org.mobilenativefoundation.store.store5.definition.PostRequest

internal class RealNetworkUpdater<Key : Any, Input : Any>(
    val realPost: PostRequest<Key, Input>,
    override val onCompletion: OnNetworkCompletion<Input>,
) : NetworkUpdater<Key, Input> {
    override suspend fun post(key: Key, input: Input): Input? = realPost(key, input)
}
