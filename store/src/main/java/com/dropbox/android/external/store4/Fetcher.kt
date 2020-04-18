package com.dropbox.android.external.store4

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.CancellationException

/**
 * The interface that defines a Fetcher, which is responsible to fetch data from a remote data
 * source. (e.g. make API calls).
 *
 * To create a fetcher, use the convenience methods ([fromValueFetcher], [fromNonFlowingFetcher],
 * [fromNonFlowingValueFetcher]).
 */
interface Fetcher<Key, Output> {
    suspend operator fun invoke(key: Key): Flow<FetcherResponse<Output>>

    companion object {
        /**
         * Creates a [Fetcher] from the given flow generating function. If the returned [Flow] emits
         * an error, it will be wrapped in a [FetcherResponse.Error].
         */
        fun <Key, Output> fromValueFetcher(
            doFetch: (key: Key) -> Flow<Output>
        ): Fetcher<Key, Output> = FlowingValueFetcher(doFetch)

        /**
         * Creates a [Fetcher] from the given function. If it throws an error, the response will be
         * wrapped in a [FetcherResponse.Error].
         */
        fun <Key, Output> fromNonFlowingValueFetcher(
            doFetch: suspend (key: Key) -> Output
        ): Fetcher<Key, Output> = NonFlowingValueFetcher(doFetch)

        /**
         * Creates a [Fetcher] that returns only 1 value (e.g. a single web request, not a stream).
         * An exception thrown from this function will not be caught by Store.
         */
        fun <Key, Output> fromNonFlowingFetcher(
            doFetch: suspend (key: Key) -> FetcherResponse<Output>
        ): Fetcher<Key, Output> = NonFlowingFetcher(doFetch)
    }
}

internal class NonFlowingFetcher<Key, Output>(
    private val doFetch: suspend (key: Key) -> FetcherResponse<Output>
) : Fetcher<Key, Output> {
    override suspend fun invoke(key: Key): Flow<FetcherResponse<Output>> {
        return flow {
            emit(doFetch(key))
        }
    }
}

internal class NonFlowingValueFetcher<Key, Output>(
    private val doFetch: suspend (key: Key) -> Output
) : Fetcher<Key, Output> {
    override suspend fun invoke(key: Key): Flow<FetcherResponse<Output>> {
        return flow {
            try {
                emit(FetcherResponse.Value(doFetch(key)))
            } catch (th: Throwable) {
                if (th is CancellationException) {
                    throw th
                }
                emit(
                    FetcherResponse.Error(th)
                )
            }
        }
    }
}

internal class FlowingValueFetcher<Key, Output>(
    private val doFetch: (key: Key) -> Flow<Output>
) : Fetcher<Key, Output> {
    override suspend fun invoke(key: Key): Flow<FetcherResponse<Output>> {
        return doFetch(key).map {
            FetcherResponse.Value(it) as FetcherResponse<Output>
        }.catch {
            emit(FetcherResponse.Error(it))
        }
    }
}