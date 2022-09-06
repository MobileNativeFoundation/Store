package com.dropbox.store.definition

import com.dropbox.store.Market

typealias AnyReadCompletions = MutableList<Market.Request.Read.OnCompletion<*>>
typealias SomeReadCompletions<Output> = MutableList<Market.Request.Read.OnCompletion<Output>>