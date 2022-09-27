package com.dropbox.external.store5

import com.dropbox.external.store5.definition.Converter
import com.dropbox.external.store5.definition.PostRequest


/**
 * Posts data to remote data source.
 * @param post HTTP POST method.
 * @see [MarketWriter]
 */
data class NetworkUpdater<Key : Any, Input : Any, Output : Any>(
    val post: PostRequest<Key, Input, Output>,
    val created: Long,
    val onCompletion: OnNetworkCompletion<Output>,
    val converter: Converter<Input, Output>
)