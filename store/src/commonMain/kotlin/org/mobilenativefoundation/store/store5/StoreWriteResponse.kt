package org.mobilenativefoundation.store.store5

sealed class StoreWriteResponse {
    sealed class Success : StoreWriteResponse() {
        data class Typed<NetworkWriteResponse : Any>(val value: NetworkWriteResponse) : Success()
        data class Untyped(val value: Any) : Success()
    }

    sealed class Error : StoreWriteResponse() {
        data class Exception(val error: Throwable) : Error()
        data class Message(val message: String) : Error()
    }
}
