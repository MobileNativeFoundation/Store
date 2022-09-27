package com.dropbox.external.store5

import com.dropbox.external.store5.definition.Converter
import com.dropbox.external.store5.definition.GetRequest
import com.dropbox.external.store5.definition.PostRequest

/**
 * Gets data from remote data source.
 * @param get HTTP GET method.
 * @param post HTTP POST method. Enables eager conflict resolution.
 * @see [MarketReader]
 */
data class NetworkFetcher<Key : Any, Input : Any, Output : Any>(
    val get: GetRequest<Key, Output>,
    val post: PostRequest<Key, Input, Output>,
    val converter: Converter<Output, Input>
)