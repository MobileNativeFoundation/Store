package org.mobilenativefoundation.store.store5.internal.definition

import org.mobilenativefoundation.store.store5.StoreWriteRequest

typealias WriteRequestQueue<Key, CommonRepresentation, NetworkWriteResponse> = ArrayDeque<StoreWriteRequest<Key, CommonRepresentation, NetworkWriteResponse>>
