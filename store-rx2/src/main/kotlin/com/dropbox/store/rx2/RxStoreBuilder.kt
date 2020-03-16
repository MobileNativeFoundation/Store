package com.dropbox.store.rx2

import com.dropbox.android.external.store4.FetcherResult
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.impl.SourceOfTruth
import io.reactivex.Flowable
import io.reactivex.Scheduler
import io.reactivex.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx2.asCoroutineDispatcher

/**
 * Creates a new [StoreBuilder] from a [Flowable] fetcher.
 *
 * Use when creating a [Store] that fetches objects in an websocket-like multiple responses
 * per request protocol.
 *
 * @param fetcher a function for fetching a flow of network records.
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> StoreBuilder.Companion.fromFlowable(
    fetcher: (key: Key) -> Flowable<Output>
): StoreBuilder<Key, Output> = from { key: Key ->
    fetcher(key).asFlow()
}

/**
 * Creates a new [StoreBuilder] from a [Flowable] fetcher and a [SourceOfTruth].
 *
 * Use when creating a [Store] that fetches objects in an websocket-like multiple responses
 * per request protocol.
 *
 * @param fetcher a function for fetching a flow of network records.
 * @param sourceOfTruth a source of truth for the store.
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, Input : Any, Output : Any> StoreBuilder.Companion.fromFlowable(
    fetcher: (key: Key) -> Flowable<Input>,
    sourceOfTruth: SourceOfTruth<Key, Input, Output>
): StoreBuilder<Key, Output> = from({ key: Key -> fetcher(key).asFlow() }, sourceOfTruth)

/**
 * Creates a new [StoreBuilder] from a [Flowable] fetcher.
 *
 * Use when creating a [Store] that fetches objects in an websocket-like multiple responses
 * per request protocol.
 *
 * @param fetcher a function for fetching a flow of network records.
 * @param fetcherTransformer used to translate your fetcher's return value to success value
 * or error in the case that your fetcher does not communicate errors through exceptions
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, RawOutput : Any, Output: Any> StoreBuilder.Companion.fromFlowable(
    fetcher: (key: Key) -> Flowable<RawOutput>,
    fetcherTransformer: (RawOutput) -> FetcherResult<Output>
): StoreBuilder<Key, Output> = from(
    fetcher = { key: Key -> fetcher(key).asFlow() },
    fetcherTransformer = fetcherTransformer
)

/**
 * Creates a new [StoreBuilder] from a [Flowable] fetcher and a [SourceOfTruth].
 *
 * Use when creating a [Store] that fetches objects in an websocket-like multiple responses
 * per request protocol.
 *
 * @param fetcher a function for fetching a flow of network records.
 * @param fetcherTransformer used to translate your fetcher's return value to success value
 * or error in the case that your fetcher does not communicate errors through exceptions
 * @param sourceOfTruth a source of truth for the store.
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, RawInput: Any, Input : Any, Output : Any> StoreBuilder.Companion.fromFlowable(
    fetcher: (key: Key) -> Flowable<RawInput>,
    fetcherTransformer: (RawInput) -> FetcherResult<Input>,
    sourceOfTruth: SourceOfTruth<Key, Input, Output>
): StoreBuilder<Key, Output> = from(
    fetcher = { key: Key -> fetcher(key).asFlow() },
    fetcherTransformer = fetcherTransformer,
    sourceOfTruth = sourceOfTruth
)

/**
 * Creates a new [StoreBuilder] from a [Single] fetcher.
 *
 * Use when creating a [Store] that fetches objects from a [Single] source that emits one response
 *
 * @param fetcher a function for fetching a [Single] network response for a [Key]
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> StoreBuilder.Companion.fromSingle(
    fetcher: (key: Key) -> Single<Output>
): StoreBuilder<Key, Output> =
    from { key: Key -> fetcher(key).toFlowable().asFlow() }

/**
 * Creates a new [StoreBuilder] from a [Single] fetcher and a [SourceOfTruth].
 *
 * Use when creating a [Store] that fetches objects from a [Single] source that emits one response
 *
 * @param fetcher a function for fetching a [Single] network response for a [Key]
 * @param sourceOfTruth a source of truth for the store.
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, Input : Any, Output : Any> StoreBuilder.Companion.fromSingle(
    fetcher: (key: Key) -> Single<Input>,
    sourceOfTruth: SourceOfTruth<Key, Input, Output>
): StoreBuilder<Key, Output> =
    from({ key: Key -> fetcher(key).toFlowable().asFlow() }, sourceOfTruth)

/**
 * Creates a new [StoreBuilder] from a [Single] fetcher.
 *
 * Use when creating a [Store] that fetches objects from a [Single] source that emits one response
 *
 * @param fetcher a function for fetching a [Single] network response for a [Key]
 * @param fetcherTransformer used to translate your fetcher's return value to success value
 * or error in the case that your fetcher does not communicate errors through exceptions
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, RawOutput: Any, Output : Any> StoreBuilder.Companion.fromSingle(
    fetcher: (key: Key) -> Single<RawOutput>,
    fetcherTransformer: (RawOutput) -> FetcherResult<Output>
): StoreBuilder<Key, Output> = from(
    fetcher = { key: Key -> fetcher(key).toFlowable().asFlow() },
    fetcherTransformer = fetcherTransformer
)

/**
 * Creates a new [StoreBuilder] from a [Single] fetcher and a [SourceOfTruth].
 *
 * Use when creating a [Store] that fetches objects from a [Single] source that emits one response
 *
 * @param fetcher a function for fetching a [Single] network response for a [Key]
 * @param fetcherTransformer used to translate your fetcher's return value to success value
 * or error in the case that your fetcher does not communicate errors through exceptions
 * @param sourceOfTruth a source of truth for the store.
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, Input : Any, RawInput : Any, Output : Any> StoreBuilder.Companion.fromSingle(
    fetcher: (key: Key) -> Single<RawInput>,
    fetcherTransformer: (RawInput) -> FetcherResult<Input>,
    sourceOfTruth: SourceOfTruth<Key, Input, Output>
): StoreBuilder<Key, Output> = from(
    fetcher = { key: Key -> fetcher(key).toFlowable().asFlow() },
    fetcherTransformer = fetcherTransformer,
    sourceOfTruth = sourceOfTruth
)

/**
 * Define what scheduler fetcher requests will be called on,
 * if a scheduler is not set Store will use [GlobalScope]
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> StoreBuilder<Key, Output>.withScheduler(
    scheduler: Scheduler
): StoreBuilder<Key, Output> {
    return scope(CoroutineScope(scheduler.asCoroutineDispatcher()))
}
