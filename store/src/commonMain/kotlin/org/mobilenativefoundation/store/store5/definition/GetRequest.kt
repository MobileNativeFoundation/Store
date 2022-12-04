package org.mobilenativefoundation.store.store5.definition

typealias GetRequest<Key, Output> = suspend (key: Key) -> Output?
