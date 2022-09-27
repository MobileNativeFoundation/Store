package com.dropbox.external.store5

/**
 * Reads from [Market].
 * @param fetcher Gets data from remote data source.
 * @see [Market].
 * @see [NetworkFetcher]
 */
data class MarketReader<Key : Any, Input : Any, Output : Any>(
    val key: Key,
    val fetcher: NetworkFetcher<Key, Input, Output>,
    val onCompletions: List<OnMarketCompletion<Output>>,
    val refresh: Boolean = false
)