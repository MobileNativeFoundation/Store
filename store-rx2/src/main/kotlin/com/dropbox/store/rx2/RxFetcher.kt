package com.dropbox.store.rx2

import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.FetcherResult
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.valueFetcher
import io.reactivex.Flowable
import io.reactivex.Single
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.reactive.asFlow

/**
 * Creates a new [Fetcher] from a [Flowable] fetcher.
 *
 * Use when creating a [Store] that fetches objects in an websocket-like multiple responses
 * per request protocol.
 *
 * @param fetcher a function for fetching a flow of network records.
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> flowableFetcher(
    fetcher: (key: Key) -> Flowable<FetcherResult<Output>>
): Fetcher<Key, Output> = { key: Key -> fetcher(key).asFlow() }

/**
 * Creates a new [Fetcher] from a [Flowable] fetcher.
 *
 * Use when creating a [Store] that fetches objects in an websocket-like multiple responses
 * per request protocol.
 *
 * @param fetcher a function for fetching a flow of network records.
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> flowableValueFetcher(
    fetcher: (key: Key) -> Flowable<Output>
): Fetcher<Key, Output> = valueFetcher { key: Key -> fetcher(key).asFlow() }

/**
 * Creates a new [Fetcher] from a [Single] fetcher.
 *
 * Use when creating a [Store] that fetches objects from a [Single] source that emits one response
 *
 * @param fetcher a function for fetching a [Single] network response for a [Key]
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> singleFetcher(
    fetcher: (key: Key) -> Single<FetcherResult<Output>>
): Fetcher<Key, Output> = { key: Key -> fetcher(key).toFlowable().asFlow() }

/**
 * Creates a new [Fetcher] from a [Single] fetcher.
 *
 * Use when creating a [Store] that fetches objects from a [Single] source that emits one response
 *
 * @param fetcher a function for fetching a [Single] network response for a [Key]
 * @param fetcherTransformer used to translate your fetcher's return value to success value
 * or error in the case that your fetcher does not communicate errors through exceptions
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> singleValueFetcher(
    fetcher: (key: Key) -> Single<Output>
): Fetcher<Key, Output> = flowableValueFetcher { key: Key -> fetcher(key).toFlowable() }
