package org.mobilenativefoundation.store.store5

sealed class NetworkResult<out T : Any> {
    data class Success<T : Any>(val value: T) : NetworkResult<T>()
    data class Failure(val error: Throwable) : NetworkResult<Nothing>()
}
