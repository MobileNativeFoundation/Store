package com.dropbox.external.store5.definition

import com.dropbox.external.store5.Fetch

typealias Fetcher<Key, Input, Output> = Fetch.Request.Get<Key, Input, Output>