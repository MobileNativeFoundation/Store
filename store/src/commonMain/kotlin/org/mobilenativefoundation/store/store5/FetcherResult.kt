package org.mobilenativefoundation.store.store5

sealed class FetcherResult<out Network : Any> {
    data class Data<Network : Any>(val value: Network, val origin: String? = null) : FetcherResult<Network>()
    data class Error<E: Any>(val error: E) : FetcherResult<Nothing>()
}
