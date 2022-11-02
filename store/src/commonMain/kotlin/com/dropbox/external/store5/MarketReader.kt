package com.dropbox.external.store5

import com.dropbox.external.store5.impl.RealMarketReader

/**
 * Reads from [Market].
 * @see [Market].
 * @see [NetworkFetcher]
 */
interface MarketReader<Key : Any, Input : Any, Output : Any> {
    val key: Key
    val fetcher: NetworkFetcher<Key, Input, Output>
    val onCompletions: List<OnMarketCompletion<Output>>
    val refresh: Boolean
    val storeOnly: Boolean

    companion object {
        fun <Key : Any, Input : Any, Output : Any> by(
            key: Key,
            fetcher: NetworkFetcher<Key, Input, Output>,
            onCompletions: List<OnMarketCompletion<Output>>,
            refresh: Boolean = false,
            storeOnly: Boolean = false,
        ): MarketReader<Key, Input, Output> = RealMarketReader(key, fetcher, onCompletions, refresh, storeOnly)
    }
}