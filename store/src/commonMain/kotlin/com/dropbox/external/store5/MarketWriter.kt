package com.dropbox.external.store5

import com.dropbox.external.store5.impl.RealMarketWriter

/**
 * Writes to [Market].
 * @see [Market].
 * @see [NetworkUpdater]
 */
interface MarketWriter<Key : Any, Input : Any, Output : Any> {
    val key: Key
    val input: Input
    val updater: NetworkUpdater<Key, Input, Output>
    val onCompletions: List<OnMarketCompletion<Output>>

    companion object {
        fun <Key : Any, Input : Any, Output : Any> by(
            key: Key,
            input: Input,
            updater: NetworkUpdater<Key, Input, Output>,
            onCompletions: List<OnMarketCompletion<Output>>,
        ): MarketWriter<Key, Input, Output> = RealMarketWriter(key, input, updater, onCompletions)
    }
}