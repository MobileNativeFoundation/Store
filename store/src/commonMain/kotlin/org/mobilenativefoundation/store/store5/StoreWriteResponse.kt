package org.mobilenativefoundation.store.store5

sealed class StoreWriteResponse<NetworkWriteResponse : Any> {
    data class Success<NetworkWriteResponse : Any>(val value: NetworkWriteResponse) : StoreWriteResponse<NetworkWriteResponse>()
    sealed class Error : StoreWriteResponse<Nothing>() {
        data class Exception(val error: Throwable) : Error()
        data class Message(val message: String) : Error()
    }
}