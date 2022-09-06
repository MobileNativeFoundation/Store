package com.dropbox.store.definition

import com.dropbox.store.Fetch

typealias AnyWriteRequestQueue<Key> = ArrayDeque<Fetch.Request.Post<Key, *, *>>
typealias SomeWriteRequestQueue<Key, Input> = ArrayDeque<Fetch.Request.Post<Key, Input, *>>