package com.dropbox.external.store5.definition

typealias PostRequest<Key, Input> = suspend (key: Key, input: Input) -> Input?
