package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.NetworkUpdater
import org.mobilenativefoundation.store.store5.OnNetworkCompletion
import org.mobilenativefoundation.store.store5.definition.Converter
import org.mobilenativefoundation.store.store5.definition.PostRequest

internal class RealNetworkUpdater<Key : Any, Input : Any, Output : Any>(
    val realPost: PostRequest<Key, Input, Output>,
    override val onCompletion: OnNetworkCompletion<Output>,
    val realConverter: Converter<Input, Output>
) : NetworkUpdater<Key, Input, Output> {
    override suspend fun post(key: Key, input: Input): Output? = realPost(key, input)
    override fun converter(input: Input): Output = realConverter(input)
}
