package com.dropbox.external.store5.impl

import com.dropbox.external.store5.NetworkFetcher
import com.dropbox.external.store5.definition.GetRequest

internal class RealNetworkFetcher<Key : Any, Input : Any>(
    private val realGet: GetRequest<Key, Input>,
) : NetworkFetcher<Key, Input> {
    override suspend fun get(key: Key): Input = realGet(key)
}
