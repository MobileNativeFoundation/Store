package com.dropbox.android.external.store4

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

sealed class FetcherResult<T : Any> {
    data class Data<T : Any>(val value: T) : FetcherResult<T>()
    sealed class Error<T : Any> : FetcherResult<T>() {
        data class Exception<T : Any>(val error: Throwable) : Error<T>()
        data class Message<T : Any>(val message: String) : Error<T>()
    }
}

/**
 * Fetcher is used by [Store] to fetch network records for a given key. The return type is [Flow] to
 * allow for multiple result per request.
 * See [nonFlowFetcher] for easily translating from a regular
 * `suspend` function.
 * See [valueFetcher], [nonFlowValueFetcher] for easily translating to [FetcherResult]
 */
typealias Fetcher<Key, Output> = (key: Key) -> Flow<FetcherResult<Output>>

/**
 * "Creates" a [Fetcher] from a [Flow] source.
 *
 * Use when creating a [Store] that fetches objects in a multiple responses per request
 * network protocol (e.g Web Sockets).
 *
 * @param doFetch a source of network records.
 */
fun <Key : Any, Output : Any> fetcher(
    doFetch: (Key) -> Flow<FetcherResult<Output>>
): Fetcher<Key, Output> = doFetch

/**
 * "Creates" a [Fetcher] from a non-[Flow] source.
 *
 * Use when creating a [Store] that fetches objects in a single response per request
 * network protocol (e.g Http).
 *
 * @param doFetch a source of network records.
 */
fun <Key : Any, Output : Any> nonFlowFetcher(
    doFetch: suspend (Key) -> FetcherResult<Output>
): Fetcher<Key, Output> = doFetch.asFlow()

/**
 * "Creates" a [Fetcher] from a [Flow] source and translate the results to a [FetcherResult].
 *
 * Emitted values will be wrapped in [FetcherResult.Data]. if an exception disrupts the flow then
 * it will be wrapped in [FetcherResult.Error]
 *
 * Use when creating a [Store] that fetches objects in a multiple responses per request
 * network protocol (e.g Web Sockets).
 *
 * @param doFetch a source of network records.
 */
@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> valueFetcher(
    doFetch: (Key) -> Flow<Output>
): Fetcher<Key, Output> = { key: Key ->
    doFetch(key).map { FetcherResult.Data(it) as FetcherResult<Output> }
    .catch { th: Throwable ->
        emit(FetcherResult.Error.Exception(th))
    }
}

/**
 * "Creates" a [Fetcher] from a non-[Flow] source and translate the results to a [FetcherResult].
 *
 * Emitted values will be wrapped in [FetcherResult.Data]. if an exception disrupts the flow then
 * it will be wrapped in [FetcherResult.Error]
 *
 * Use when creating a [Store] that fetches objects in a single response per request
 * network protocol (e.g Http).
 *
 * @param doFetch a source of network records.
 */
@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> nonFlowValueFetcher(
    doFetch: suspend (key: Key) -> Output
): Fetcher<Key, Output> = valueFetcher(doFetch.asFlow())

private fun <Key, Value> (suspend (key: Key) -> Value).asFlow() = { key: Key ->
    flow {
        emit(invoke(key))
    }
}
