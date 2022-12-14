package org.mobilenativefoundation.store.store5

sealed class MarketResponse {
    object Loading : MarketResponse()
    data class Success<Output>(val value: Output, val origin: Origin) : MarketResponse()
    data class WriteSuccess(val origin: Origin = Origin.LocalWrite) : MarketResponse()

    data class Failure(val error: Throwable, val origin: Origin) : MarketResponse()
    object Empty : MarketResponse()

    companion object {
        enum class Origin {
            Store,
            Network,
            LocalWrite
        }
    }
}
