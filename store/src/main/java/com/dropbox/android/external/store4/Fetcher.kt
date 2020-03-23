package com.dropbox.android.external.store4

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.CancellationException

interface Fetcher<Key, Output> {
    suspend operator fun invoke(key: Key): Flow<FetcherResponse<Output>>

    companion object {
        fun <Key, Output> fromValueFetcher(
            doFetch: (key: Key) -> Flow<Output>
        ): Fetcher<Key, Output> = FlowingValueFetcher(doFetch)

        fun <Key, Output> fromNonFlowingValueFetcher(
            doFetch: suspend (key: Key) -> Output
        ): Fetcher<Key, Output> = NonFlowingValueFetcher(doFetch)

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
            emit(FetcherResponse.Error<Output>(it))
        }
    }
}