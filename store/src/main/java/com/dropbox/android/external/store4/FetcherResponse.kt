package com.dropbox.android.external.store4

sealed class FetcherResponse<T> {
    class Value<T>(
        val value: T
    ) : FetcherResponse<T>()

    // TODO support non-throwable errors ?
    class Error<T>(
        val error: Throwable
    ) : FetcherResponse<T>()
}