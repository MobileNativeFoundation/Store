package com.dropbox.external.store5

import com.dropbox.external.store5.definition.Converter
import com.dropbox.external.store5.definition.PostRequest


/**
 * Posts to remote data source.
 * @see [Writer]
 */
data class Updater<Key : Any, Input : Any, Output : Any>(
    val post: PostRequest<Key, Input, Output>,
    val created: Long,
    val onCompletion: OnRemoteCompletion<Output>,
    val converter: Converter<Input, Output>
)