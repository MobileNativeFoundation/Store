package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.StoreWriteRequest

data class RealStoreWriteRequest<Key : Any, Output : Any, Response : Any>(
    override val key: Key,
    override val value: Output,
    override val created: Long,
    override val onCompletions: List<OnStoreWriteCompletion>?,
) : StoreWriteRequest<Key, Output, Response>
