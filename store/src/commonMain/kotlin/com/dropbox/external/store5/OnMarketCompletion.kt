package com.dropbox.external.store5

data class OnMarketCompletion<T : Any>(
    val onSuccess: (MarketResponse.Success<T>) -> Unit,
    val onFailure: (MarketResponse.Failure) -> Unit
)
