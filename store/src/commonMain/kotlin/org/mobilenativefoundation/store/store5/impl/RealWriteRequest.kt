package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.WriteRequest
import org.mobilenativefoundation.store.store5.OnMarketCompletion

data class RealWriteRequest<Key : Any, Input : Any, Output : Any>(
    override val key: Key,
    override val input: Input,
    override val created: Long,
    override val onCompletions: List<OnMarketCompletion<Output>>
) : WriteRequest<Key, Input, Output>
