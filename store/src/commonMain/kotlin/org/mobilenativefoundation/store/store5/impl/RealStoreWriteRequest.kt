package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.StoreWriteRequest

data class RealStoreWriteRequest<Key : Any, Common : Any, Response : Any>(
    override val key: Key,
    override val input: Common,
    override val created: Long,
    override val onCompletions: List<OnStoreWriteCompletion>?
) : StoreWriteRequest<Key, Common, Response>
