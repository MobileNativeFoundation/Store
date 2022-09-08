package com.dropbox.external.store5.definition

import com.dropbox.external.store5.Market

typealias AnyReadCompletionsQueue = MutableList<Market.Request.Reader.OnCompletion<*>>
typealias SomeReadCompletionsQueue<Output> = MutableList<Market.Request.Reader.OnCompletion<Output>>