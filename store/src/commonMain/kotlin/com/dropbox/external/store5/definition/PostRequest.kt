package com.dropbox.external.store5.definition

typealias PostRequest<Key, Input, Output> = suspend (key: Key, input: Input) -> Output?
