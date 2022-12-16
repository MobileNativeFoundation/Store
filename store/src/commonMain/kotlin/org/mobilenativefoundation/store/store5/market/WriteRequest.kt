package org.mobilenativefoundation.store.store5.market

import org.mobilenativefoundation.store.store5.market.impl.RealWriteRequest

/**
 * Writes to [Market].
 * @see [Market].
 * @see [NetworkUpdater]
 */
interface WriteRequest<Key : Any, CommonRepresentation : Any> {
    val key: Key
    val input: CommonRepresentation
    val created: Long
    val onCompletions: List<OnMarketCompletion<CommonRepresentation>>

    companion object {
        fun <Key : Any, CommonRepresentation : Any> of(
            key: Key,
            input: CommonRepresentation,
            created: Long,
            onCompletions: List<OnMarketCompletion<CommonRepresentation>>,
        ): WriteRequest<Key, CommonRepresentation> = RealWriteRequest(key, input, created, onCompletions)
    }
}
