package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.store5.impl.RealWriteRequest

/**
 * Writes to [Market].
 * @see [Market].
 * @see [NetworkUpdater]
 */
interface WriteRequest<Key : Any, Input : Any, Output : Any> {
    val key: Key
    val input: Input
    val created: Long
    val onCompletions: List<OnMarketCompletion<Output>>

    companion object {
        fun <Key : Any, Input : Any, Output : Any> of(
            key: Key,
            input: Input,
            created: Long,
            onCompletions: List<OnMarketCompletion<Output>>,
        ): WriteRequest<Key, Input, Output> = RealWriteRequest(key, input, created, onCompletions)
    }
}
