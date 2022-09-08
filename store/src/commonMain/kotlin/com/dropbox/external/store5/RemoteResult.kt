package com.dropbox.external.store5

sealed class RemoteResult<out T : Any> {
    data class Success<T : Any>(val value: T) : RemoteResult<T>()
    data class Failure(val error: Throwable) : RemoteResult<Nothing>()
}