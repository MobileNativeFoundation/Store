package com.dropbox.external.store5.definition

import com.dropbox.external.store5.Market

typealias MarketWriter<Key, Input, Output> = Market.Request.Writer<Key, Input, Output>