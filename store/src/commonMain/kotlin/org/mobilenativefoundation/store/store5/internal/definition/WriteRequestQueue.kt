package org.mobilenativefoundation.store.store5.internal.definition

import org.mobilenativefoundation.store.store5.StoreWriteRequest

typealias WriteRequestQueue<Key, Common, Response> = ArrayDeque<StoreWriteRequest<Key, Common, Response>>
