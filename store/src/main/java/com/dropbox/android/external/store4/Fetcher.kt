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
 *
 * Note: If Store does not catch exceptions thrown by a [Fetcher]. This is done in order to avoid
 * silently swallowing NPEs and such. Use [FetcherResult.Error] to communicate expected errors.
 *
 * See [nonFlowFetcher] for easily translating from a regular `suspend` function.
 * See [valueFetcher], [nonFlowValueFetcher] for easily translating to [FetcherResult] (and
 * automatically transforming exceptions into [FetcherResult.Error].
 */
typealias Fetcher<Key, Output> = (key: Key) -> Flow<FetcherResult<Output>>

/**
 * "Creates" a [Fetcher] from a [flowFactory].
 *
 * Use when creating a [Store] that fetches objects in a multiple responses per request
 * network protocol (e.g Web Sockets).
 *
 * [Store] does not catch exception thrown in [flowFactory] or in the returned [Flow]. These
 * exception will be propagated to the caller.
 *
 * @param flowFactory a factory for a [Flow]ing source of network records.
 */
fun <Key : Any, Output : Any> fetcher(
    flowFactory: (Key) -> Flow<FetcherResult<Output>>
): Fetcher<Key, Output> = flowFactory

/**
 * "Creates" a [Fetcher] from a non-[Flow] source.
 *
 * Use when creating a [Store] that fetches objects in a single response per request network
 * protocol (e.g Http).
 *
 * [Store] does not catch exception thrown in [doFetch]. These exception will be propagated to the
 * caller.
 *
 * @param doFetch a source of network records.
 */
fun <Key : Any, Output : Any> nonFlowFetcher(
    doFetch: suspend (Key) -> FetcherResult<Output>
): Fetcher<Key, Output> = doFetch.asFlow()

/**
 * "Creates" a [Fetcher] from a [flowFactory] and translate the results to a [FetcherResult].
 *
 * Emitted values will be wrapped in [FetcherResult.Data]. if an exception disrupts the flow then
 * it will be wrapped in [FetcherResult.Error]. Exceptions thrown in [flowFactory] itself are not
 * caught and will be returned to the caller.
 *
 * Use when creating a [Store] that fetches objects in a multiple responses per request
 * network protocol (e.g Web Sockets).
 *
 * @param flowFactory a factory for a [Flow]ing source of network records.
 */
@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> valueFetcher(
    flowFactory: (Key) -> Flow<Output>
): Fetcher<Key, Output> = { key: Key ->
    flowFactory(key).map { FetcherResult.Data(it) as FetcherResult<Output> }
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
