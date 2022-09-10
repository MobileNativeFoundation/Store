package com.dropbox.external.store5

import kotlinx.serialization.KSerializer

/**
 * Reads from [Market].
 * @param fetcher Gets from remote data source.
 * @see [Market].
 * @see [Fetcher]
 */
data class Reader<Key : Any, Input : Any, Output : Any>(
    val key: Key,
    val fetcher: Fetcher<Key, Input, Output>,
    val serializer: KSerializer<Output>,
    val onCompletions: List<OnMarketCompletion<Output>>,
    val refresh: Boolean = false
)