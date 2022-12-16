package org.mobilenativefoundation.store.store5.market

sealed class NetworkReadResult<out T : Any> {
    data class Success<T : Any>(val value: T) : NetworkReadResult<T>()
    data class Failure(val error: Throwable) : NetworkReadResult<Nothing>()
}
