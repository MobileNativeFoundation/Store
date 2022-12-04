package com.dropbox.external.store5

import com.dropbox.external.store5.impl.RealMarketReader

/**
 * Reads from [Market].
 * @see [Market].
 * @see [NetworkFetcher]
 */
interface ReadRequest<Key : Any, Input : Any, Output : Any> {
    val key: Key
    val onCompletions: List<OnMarketCompletion<Output>>
    val validator: ItemValidator<Output>?
    val refresh: Boolean

    companion object {
        fun <Key : Any, Input : Any, Output : Any> of(
            key: Key,
            onCompletions: List<OnMarketCompletion<Output>>,
            validator: ItemValidator<Output>? = null,
            refresh: Boolean,
        ): ReadRequest<Key, Input, Output> =
            RealMarketReader(key, onCompletions, validator, refresh)
    }
}
