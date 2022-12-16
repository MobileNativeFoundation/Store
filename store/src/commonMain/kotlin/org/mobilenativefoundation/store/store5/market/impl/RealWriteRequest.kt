package org.mobilenativefoundation.store.store5.market.impl

import org.mobilenativefoundation.store.store5.market.OnMarketCompletion
import org.mobilenativefoundation.store.store5.market.WriteRequest

data class RealWriteRequest<Key : Any, CommonRepresentation : Any>(
    override val key: Key,
    override val input: CommonRepresentation,
    override val created: Long,
    override val onCompletions: List<OnMarketCompletion<CommonRepresentation>>
) : WriteRequest<Key, CommonRepresentation>
