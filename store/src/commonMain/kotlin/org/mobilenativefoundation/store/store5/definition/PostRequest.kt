package org.mobilenativefoundation.store.store5.definition

typealias PostRequest<Key, Input, Output> = suspend (key: Key, input: Input) -> Output?
