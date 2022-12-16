package org.mobilenativefoundation.store.store5

sealed class FetcherResult<out NetworkRepresentation : Any> {
    data class Data<NetworkRepresentation : Any>(val value: NetworkRepresentation) : FetcherResult<NetworkRepresentation>()
    sealed class Error : FetcherResult<Nothing>() {
        data class Exception(val error: Throwable) : Error()
        data class Message(val message: String) : Error()
    }
}