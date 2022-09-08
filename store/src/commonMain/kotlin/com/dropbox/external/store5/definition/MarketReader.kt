package com.dropbox.external.store5.definition

import com.dropbox.external.store5.Market

typealias MarketReader<Key, Input, Output> = Market.Request.Reader<Key, Input, Output>