package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.ItemValidator
import org.mobilenativefoundation.store.store5.ReadRequest
import org.mobilenativefoundation.store.store5.OnMarketCompletion

internal data class RealMarketReader<Key : Any, Input : Any, Output : Any>(
    override val key: Key,
    override val onCompletions: List<OnMarketCompletion<Output>>,
    override val validator: ItemValidator<Output>?,
    override val refresh: Boolean = false
) : ReadRequest<Key, Input, Output>
