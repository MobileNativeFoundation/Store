package com.dropbox.android.external.store4

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

typealias Fetcher<Key, Output> = (key: Key) -> Flow<FetcherResult<Output>>

fun <Key : Any, Output : Any> fetcher(
    doFetch: (Key) -> Flow<FetcherResult<Output>>
): Fetcher<Key, Output> = doFetch

fun <Key : Any, Output : Any> nonFlowFetcher(
    doFetch: suspend (Key) -> FetcherResult<Output>
): Fetcher<Key, Output> = doFetch.asFlow()

@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> valueFetcher(
    doFetch: (Key) -> Flow<Output>
): Fetcher<Key, Output> = TransformingFetcher(doFetch, ::exceptionsAsErrors)

@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> nonFlowValueFetcher(
    doFetch: suspend (key: Key) -> Output
): Fetcher<Key, Output> = valueFetcher(doFetch.asFlow())

private fun <Key, Value> (suspend (key: Key) -> Value).asFlow() = { key: Key ->
    flow {
        emit(invoke(key))
    }
}

@ExperimentalCoroutinesApi
private fun <Input : Any> exceptionsAsErrors(input: Flow<Input>): Flow<FetcherResult<Input>> =
    input
        .map { FetcherResult.Data(it) as FetcherResult<Input> }
        .catch { th: Throwable ->
            emit(FetcherResult.Error.Exception(th))
        }

internal class TransformingFetcher<Key : Any, Input : Any, Output : Any>(
    private val doFetch: (Key) -> Flow<Input>,
    private val doTransform: (Flow<Input>) -> Flow<FetcherResult<Output>>
) : Fetcher<Key, Output> {
    override fun invoke(key: Key): Flow<FetcherResult<Output>> =
        doTransform(doFetch(key))
}
