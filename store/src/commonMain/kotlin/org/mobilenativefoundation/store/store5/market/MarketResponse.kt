package org.mobilenativefoundation.store.store5.market

sealed class MarketResponse<out CommonRepresentation : Any> {
    object Loading : MarketResponse<Nothing>()
    data class Success<CommonRepresentation : Any>(val value: CommonRepresentation, val origin: Origin) : MarketResponse<CommonRepresentation>()
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
