package org.mobilenativefoundation.store.store5.definition

typealias PostRequest<Key, Input> = suspend (key: Key, input: Input) -> Input?
