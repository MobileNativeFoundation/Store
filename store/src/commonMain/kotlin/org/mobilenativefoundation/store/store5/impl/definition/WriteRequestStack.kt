package org.mobilenativefoundation.store.store5.impl.definition

import org.mobilenativefoundation.store.store5.StoreWriteRequest

typealias WriteRequestStack<Key, Output, Response> = ArrayDeque<StoreWriteRequest<Key, Output, Response>>
