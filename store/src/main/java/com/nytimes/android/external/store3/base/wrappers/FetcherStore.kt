package com.nytimes.android.external.store3.base.wrappers

import com.nytimes.android.external.store3.base.Fetcher
import com.nytimes.android.external.store3.base.impl.Store
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

internal class FetcherStore<Raw, Key>(
        private val fetcher: Fetcher<Raw, Key>
) : Store<Raw, Key> {

    private val subject = BroadcastChannel<Pair<Key, Raw>?>(CONFLATED).apply {
        //a conflated channel always maintains the last element, the stream method ignore this element.
        //Here we add an empty element that will be ignored later
        offer(null)
    }

    override suspend fun get(key: Key): Raw = fresh(key)

    override suspend fun fresh(key: Key): Raw =
            fetcher.fetch(key).also { fetchedValue ->
                subject.send(key to fetchedValue)
            }

    @FlowPreview
    override fun stream(): Flow<Pair<Key, Raw>> =
            subject.asFlow()
                    //ignore first element so only new elements are returned
                    .drop(1)
                    .map { it!! }

    override suspend fun clearMemory() {
    }

    override suspend fun clear(key: Key) {
    }
}

