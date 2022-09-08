package com.dropbox.external.store5.definition


import com.dropbox.external.store5.Fetch

typealias Updater<Key, Input, Output> = Fetch.Request.Post<Key, Input, Output>