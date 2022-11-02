package com.dropbox.external.store5.impl

import com.dropbox.external.store5.MarketReader
import com.dropbox.external.store5.NetworkFetcher
import com.dropbox.external.store5.OnMarketCompletion

internal data class RealMarketReader<Key : Any, Input : Any, Output : Any>(
    override val key: Key,
    override val fetcher: NetworkFetcher<Key, Input, Output>,
    override val onCompletions: List<OnMarketCompletion<Output>>,
    override val refresh: Boolean = false,
    override val storeOnly: Boolean = false,
) : MarketReader<Key, Input, Output>