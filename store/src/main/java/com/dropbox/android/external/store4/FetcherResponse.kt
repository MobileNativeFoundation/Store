package com.dropbox.android.external.store4

/**
 * Value type emitted by the [Fetcher]'s `Flow`.
 */
sealed class FetcherResponse<T> {
    /**
     * Success result, should include a non-null value.
     */
    class Value<T>(
        val value: T
    ) : FetcherResponse<T>()

    /**
     * Error result, it should contain the error information
     */
    // TODO support non-throwable errors ?
    class Error<T>(
        val error: Throwable
    ) : FetcherResponse<T>()
}