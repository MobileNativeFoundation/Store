package org.mobilenativefoundation.store.store5.market.impl

import org.mobilenativefoundation.store.store5.market.ItemValidator
import org.mobilenativefoundation.store.store5.market.OnMarketCompletion
import org.mobilenativefoundation.store.store5.market.ReadRequest

internal data class RealMarketReader<Key : Any, CommonRepresentation : Any>(
    override val key: Key,
    override val onCompletions: List<OnMarketCompletion<CommonRepresentation>>,
    override val validator: ItemValidator<CommonRepresentation>?,
    override val refresh: Boolean = false
) : ReadRequest<Key, CommonRepresentation>
