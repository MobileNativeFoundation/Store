package com.dropbox.external.store5.definition

typealias GetRequest<Key, Output> = suspend (key: Key) -> Output?
