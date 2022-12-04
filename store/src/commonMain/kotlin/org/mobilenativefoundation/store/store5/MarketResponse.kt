package org.mobilenativefoundation.store.store5

sealed class MarketResponse<out Output> {
    object Loading : MarketResponse<Nothing>()
    data class Success<Output>(val value: Output, val origin: Origin) : MarketResponse<Output>()
    data class Failure(val error: Throwable, val origin: Origin) : MarketResponse<Nothing>()
    object Empty : MarketResponse<Nothing>()

    companion object {
        enum class Origin {
            Store,
            Network,
            LocalWrite
        }
    }
}
