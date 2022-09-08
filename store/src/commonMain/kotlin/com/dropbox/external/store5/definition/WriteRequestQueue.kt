package com.dropbox.external.store5.definition

import com.dropbox.external.store5.Fetch

typealias AnyWriteRequestQueue<Key> = ArrayDeque<Fetch.Request.Post<Key, *, *>>
typealias SomeWriteRequestQueue<Key, Input> = ArrayDeque<Fetch.Request.Post<Key, Input, *>>