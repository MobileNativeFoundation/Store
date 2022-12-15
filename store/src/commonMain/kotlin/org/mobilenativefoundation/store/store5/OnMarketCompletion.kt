package org.mobilenativefoundation.store.store5

data class OnMarketCompletion<CommonRepresentation : Any>(
    val onSuccess: (MarketResponse.Success<CommonRepresentation>) -> Unit,
    val onFailure: (MarketResponse.Failure) -> Unit
)
