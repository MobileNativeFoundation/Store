package org.mobilenativefoundation.store.store5

import org.mobilenativefoundation.store.store5.impl.RealMarketReader

/**
 * Reads from [Market].
 * @see [Market].
 * @see [NetworkFetcher]
 */
interface ReadRequest<Key : Any, CommonRepresentation : Any> {
    val key: Key
    val onCompletions: List<OnMarketCompletion<CommonRepresentation>>
    val validator: ItemValidator<CommonRepresentation>?
    val refresh: Boolean

    companion object {
        fun <Key : Any, CommonRepresentation : Any> of(
            key: Key,
            onCompletions: List<OnMarketCompletion<CommonRepresentation>>,
            validator: ItemValidator<CommonRepresentation>? = null,
            refresh: Boolean,
        ): ReadRequest<Key, CommonRepresentation> =
            RealMarketReader(key, onCompletions, validator, refresh)
    }
}
