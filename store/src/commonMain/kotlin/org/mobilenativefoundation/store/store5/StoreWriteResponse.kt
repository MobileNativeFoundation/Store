package org.mobilenativefoundation.store.store5

sealed class StoreWriteResponse {
    sealed class Success : StoreWriteResponse() {
        data class Typed<Response : Any>(val value: Response) : Success()
        data class Untyped(val value: Any) : Success()
    }

    data class Error<E: Any>(val error: E) : StoreWriteResponse()
}
