package org.mobilenativefoundation.store.store5

sealed class UpdaterResult<out NetworkWriteResponse : Any> {
    data class Success<NetworkWriteResponse : Any>(val value: NetworkWriteResponse) : UpdaterResult<NetworkWriteResponse>()
    sealed class Error : UpdaterResult<Nothing>() {
        data class Exception(val error: Throwable) : Error()
        data class Message(val message: String) : Error()
    }

    fun isOk() = this is Success
}
