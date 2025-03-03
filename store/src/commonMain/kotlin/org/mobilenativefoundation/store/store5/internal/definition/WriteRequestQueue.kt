package org.mobilenativefoundation.store.store5.internal.definition

import org.mobilenativefoundation.store.store5.StoreWriteRequest

typealias WriteRequestQueue<Key, Output, Response> =
  ArrayDeque<StoreWriteRequest<Key, Output, Response>>
