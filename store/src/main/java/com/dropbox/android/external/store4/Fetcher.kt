package com.dropbox.android.external.store4

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.CancellationException

interface Fetcher<Key : Any, Output : Any> {
    operator fun invoke(key: Key): Flow<FetcherResult<Output>>

    companion object {
        fun <Key : Any, Output : Any> from(
            doFetch: (Key) -> Flow<FetcherResult<Output>>
        ): Fetcher<Key, Output> = RealFetcher(
            doFetch = doFetch,
            doTransform = { it }
        )

        fun <Key : Any, Output : Any> fromNonFlow(
            doFetch: suspend (key: Key) -> FetcherResult<Output>
        ): Fetcher<Key, Output> = RealFetcher(
            doFetch = doFetch.asFlow(),
            doTransform = { it }
        )
    }
}

@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> Fetcher.Companion.exceptionsAsErrors(
    doFetch: (Key) -> Flow<Output>
): Fetcher<Key, Output> = RealFetcher(doFetch, ::exceptionsAsErrors)

@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> Fetcher.Companion.exceptionsAsErrorsNonFlow(
    doFetch: suspend (key: Key) -> Output
): Fetcher<Key, Output> = exceptionsAsErrors(doFetch.asFlow())

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
            if (th is CancellationException) {
                throw th
            }
            emit(FetcherResult.Error.Exception(th))
        }

internal class RealFetcher<Key : Any, Input : Any, Output : Any>(
    private val doFetch: (Key) -> Flow<Input>,
    private val doTransform: (Flow<Input>) -> Flow<FetcherResult<Output>>
) : Fetcher<Key, Output> {
    override fun invoke(key: Key): Flow<FetcherResult<Output>> =
        doTransform(doFetch(key))
}
