package org.mobilenativefoundation.store.store5

sealed class FetcherResult<out Network : Any> {
    data class Data<Network : Any>(val value: Network) : FetcherResult<Network>()
    sealed class Error : FetcherResult<Nothing>() {
        data class Exception(val error: Throwable) : Error()
        data class Message(val message: String) : Error()
    }
}
