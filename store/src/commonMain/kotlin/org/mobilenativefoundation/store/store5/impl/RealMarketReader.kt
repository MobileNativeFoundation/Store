package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.ItemValidator
import org.mobilenativefoundation.store.store5.OnMarketCompletion
import org.mobilenativefoundation.store.store5.ReadRequest

internal data class RealMarketReader<Key : Any, CommonRepresentation : Any>(
    override val key: Key,
    override val onCompletions: List<OnMarketCompletion<CommonRepresentation>>,
    override val validator: ItemValidator<CommonRepresentation>?,
    override val refresh: Boolean = false
) : ReadRequest<Key, CommonRepresentation>
