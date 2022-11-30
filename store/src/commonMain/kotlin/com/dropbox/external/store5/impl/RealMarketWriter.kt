package com.dropbox.external.store5.impl

import com.dropbox.external.store5.MarketWriter
import com.dropbox.external.store5.OnMarketCompletion

data class RealMarketWriter<Key : Any, Input : Any, Output : Any>(
    override val key: Key,
    override val input: Input,
    override val created: Long,
    override val onCompletions: List<OnMarketCompletion<Output>>
) : MarketWriter<Key, Input, Output>
