package com.dropbox.external.store5

import kotlinx.serialization.KSerializer

/**
 * Writes to [Market].
 * @param updater Posts to remote data source.
 * @see [Market].
 * @see [Updater]
 */
data class Writer<Key : Any, Input : Any, Output : Any>(
    val key: Key,
    val input: Input,
    val updater: Updater<Key, Input, Output>,
    val serializer: KSerializer<Input>,
    val onCompletions: List<OnMarketCompletion<Output>>
)