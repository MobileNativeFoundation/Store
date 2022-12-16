package org.mobilenativefoundation.store.store5.market.definition

typealias GetRequest<Key, Output> = suspend (key: Key) -> Output?
