package com.dropbox.store.definition

import com.dropbox.store.Market
import kotlinx.coroutines.flow.MutableSharedFlow

typealias AnyBroadcast = MutableSharedFlow<Market.Response<*>>
typealias SomeBroadcast<T> = MutableSharedFlow<Market.Response<T>>