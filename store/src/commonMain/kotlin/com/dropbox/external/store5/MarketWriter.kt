package com.dropbox.external.store5

/**
 * Writes to [Market].
 * @param updater Posts to remote data source.
 * @see [Market].
 * @see [NetworkUpdater]
 */
data class MarketWriter<Key : Any, Input : Any, Output : Any>(
    val key: Key,
    val input: Input,
    val updater: NetworkUpdater<Key, Input, Output>,
    val onCompletions: List<OnMarketCompletion<Output>>
)