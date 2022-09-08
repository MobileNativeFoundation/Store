package com.dropbox.external.store5

import com.dropbox.external.store5.definition.Converter
import com.dropbox.external.store5.definition.GetRequest
import com.dropbox.external.store5.definition.PostRequest


/**
 * Gets from remote data source.
 * @see [Reader]
 */
data class Fetcher<Key : Any, Input : Any, Output : Any>(
    val get: GetRequest<Key, Output>,
    val post: PostRequest<Key, Input, Output>,
    val converter: Converter<Output, Input>
)