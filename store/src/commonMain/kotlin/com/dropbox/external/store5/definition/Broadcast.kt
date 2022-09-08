package com.dropbox.external.store5.definition

import com.dropbox.external.store5.Market
import kotlinx.coroutines.flow.MutableSharedFlow

typealias AnyBroadcast = MutableSharedFlow<Market.Response<*>>
typealias SomeBroadcast<T> = MutableSharedFlow<Market.Response<T>>