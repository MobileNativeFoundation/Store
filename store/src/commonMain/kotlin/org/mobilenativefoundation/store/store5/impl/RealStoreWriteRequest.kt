package org.mobilenativefoundation.store.store5.impl

import org.mobilenativefoundation.store.store5.StoreWriteRequest

data class RealStoreWriteRequest<Key : Any, CommonRepresentation : Any, NetworkWriteResponse : Any>(
    override val key: Key,
    override val input: CommonRepresentation,
    override val created: Long,
    override val onCompletions: List<OnStoreWriteCompletion<NetworkWriteResponse>>?
) : StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse>
