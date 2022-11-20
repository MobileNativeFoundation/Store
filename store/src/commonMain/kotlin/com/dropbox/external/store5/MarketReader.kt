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
    val validator: GoodValidator<Output>?
    val onCompletions: List<OnMarketCompletion<Output>>
    val refresh: Boolean

    companion object {
        fun <Key : Any, Input : Any, Output : Any> by(
            key: Key,
            fetcher: NetworkFetcher<Key, Input, Output>,
            validator: GoodValidator<Output>? = null,
            onCompletions: List<OnMarketCompletion<Output>>,
            refresh: Boolean,
        ): MarketReader<Key, Input, Output> = RealMarketReader(key, fetcher, validator, onCompletions, refresh)
    }
}