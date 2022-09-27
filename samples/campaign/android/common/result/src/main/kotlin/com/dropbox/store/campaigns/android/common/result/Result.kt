package com.dropbox.store.campaigns.android.common.result

sealed class Result<out D : Any> {
    data class Success<out D : Any>(
        val value: D
    ) : Result<D>()

    data class Failure(
        val error: Throwable
    ) : Result<Nothing>()
}