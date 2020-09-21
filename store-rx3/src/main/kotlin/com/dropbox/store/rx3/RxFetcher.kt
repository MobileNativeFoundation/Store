package com.dropbox.store.rx3

import com.dropbox.android.external.store4.Fetcher
import com.dropbox.android.external.store4.FetcherResult
import com.dropbox.android.external.store4.Store
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.reactive.asFlow

/**
 * Creates a new [Fetcher] from a [flowableFactory].
 *
 * [Store] does not catch exception thrown in [flowableFactory] or in the returned [Flowable]. These
 * exception will be propagated to the caller.
 *
 * Use when creating a [Store] that fetches objects in a multiple responses per request
 * network protocol (e.g Web Sockets).
 *
 * @param flowableFactory a factory for a [Flowable] source of network records.
 */
fun <Key : Any, Output : Any> Fetcher.Companion.ofResultFlowable(
    flowableFactory: (key: Key) -> Flowable<FetcherResult<Output>>
): Fetcher<Key, Output> = ofResultFlow { key: Key -> flowableFactory(key).asFlow() }

/**
 * "Creates" a [Fetcher] from a [singleFactory].
 *
 * [Store] does not catch exception thrown in [singleFactory] or in the returned [Single]. These
 * exception will be propagated to the caller.
 *
 * Use when creating a [Store] that fetches objects in a single response per request network
 * protocol (e.g Http).
 *
 * @param singleFactory a factory for a [Single] source of network records.
 */
fun <Key : Any, Output : Any> Fetcher.Companion.ofResultSingle(
    singleFactory: (key: Key) -> Single<FetcherResult<Output>>
): Fetcher<Key, Output> = ofResultFlowable { key: Key -> singleFactory(key).toFlowable() }

/**
 * "Creates" a [Fetcher] from a [flowableFactory] and translate the results to a [FetcherResult].
 *
 * Emitted values will be wrapped in [FetcherResult.Data]. if an exception disrupts the stream then
 * it will be wrapped in [FetcherResult.Error]. Exceptions thrown in [flowableFactory] itself are
 * not caught and will be returned to the caller.
 *
 * Use when creating a [Store] that fetches objects in a multiple responses per request
 * network protocol (e.g Web Sockets).
 *
 * @param flowFactory a factory for a [Flowable] source of network records.
 */
fun <Key : Any, Output : Any> Fetcher.Companion.ofFlowable(
    flowableFactory: (key: Key) -> Flowable<Output>
): Fetcher<Key, Output> = ofFlow { key: Key -> flowableFactory(key).asFlow() }

/**
 * Creates a new [Fetcher] from a [singleFactory] and translate the results to a [FetcherResult].
 *
 * The emitted value will be wrapped in [FetcherResult.Data]. if an exception is returned then
 * it will be wrapped in [FetcherResult.Error]. Exceptions thrown in [singleFactory] itself are
 * not caught and will be returned to the caller.
 *
 * Use when creating a [Store] that fetches objects in a single response per request network
 * protocol (e.g Http).
 *
 * @param singleFactory a factory for a [Single] source of network records.
 */
fun <Key : Any, Output : Any> Fetcher.Companion.ofSingle(
    singleFactory: (key: Key) -> Single<Output>
): Fetcher<Key, Output> = ofFlowable { key: Key -> singleFactory(key).toFlowable() }
