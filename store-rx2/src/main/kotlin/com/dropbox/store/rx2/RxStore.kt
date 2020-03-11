package com.dropbox.store.rx2

import com.dropbox.android.external.store4.ExperimentalStoreApi
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.android.external.store4.impl.SourceOfTruth
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Scheduler
import io.reactivex.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx2.asCoroutineDispatcher
import kotlinx.coroutines.rx2.asFlowable
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.rx2.rxCompletable

/**
 * Return a [Flowable] for the given key
 * @param request - see [StoreRequest] for configurations
 */
@ExperimentalCoroutinesApi
fun <Key : Any, Output : Any> Store<Key, Output>.observe(request: StoreRequest<Key>): Flowable<StoreResponse<Output>> =
    stream(request).asFlowable()

/**
 * Purge a particular entry from memory and disk cache.
 * Persistent storage will only be cleared if a delete function was passed to
 * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
 */
fun <Key : Any, Output : Any> Store<Key, Output>.observeClear(key: Key): Completable =
    rxCompletable { clear(key) }

/**
 * Purge all entries from memory and disk cache.
 * Persistent storage will only be cleared if a deleteAll function was passed to
 * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
 */
@ExperimentalStoreApi
fun <Key : Any, Output : Any> Store<Key, Output>.observeClearAll(): Completable =
    rxCompletable { clearAll() }

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

/**
 * Creates a (Non Flow) [Single] source of truth that is accessible via [reader], [writer],
 * [delete], and [deleteAll].
 *
 * @see com.dropbox.android.external.store4.StoreBuilder.persister
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, Input : Any, Output : Any> SourceOfTruth.Companion.fromSinglePersister(
    reader: (Key) -> Maybe<Output>,
    writer: (Key, Input) -> Single<Unit>,
    delete: ((Key) -> Completable)? = null,
    deleteAll: (() -> Completable)? = null
): SourceOfTruth<Key, Input, Output> {
    val deleteFun: (suspend (Key) -> Unit)? =
        if (delete != null) { key -> delete(key).await() } else null
    val deleteAllFun: (suspend () -> Unit)? = deleteAll?.let { { deleteAll().await() } }
    return fromNonFlow(
        reader = { key -> reader.invoke(key).await() },
        writer = { key, output -> writer.invoke(key, output).await() },
        delete = deleteFun,
        deleteAll = deleteAllFun
    )
}

/**
 * Creates a ([Flowable]) source of truth that is accessed via [reader], [writer] and [delete].
 *
 * For maximal flexibility, [writer]'s record type ([Input]] and [reader]'s record type
 * ([Output]) are not identical. This allows us to read one type of objects from network and
 * transform them to another type when placing them in local storage.
 *
 * A source of truth is usually backed by local storage. It's purpose is to eliminate the need
 * for waiting on network update before local modifications are available (via [Store.stream]).
 *
 * @param reader reads records from the source of truth
 * @param writer writes records **coming in from the fetcher (network)** to the source of truth.
 * Writing local user updates to the source of truth via [Store] is currently not supported.
 * @param delete deletes records in the source of truth for the give key
 * @param deleteAll deletes all records in the source of truth
 *
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, Input : Any, Output : Any> SourceOfTruth.Companion.fromFlowablePersister(
    reader: (Key) -> Flowable<Output>,
    writer: (Key, Input) -> Single<Unit>,
    delete: ((Key) -> Completable)? = null,
    deleteAll: (() -> Completable)? = null
): SourceOfTruth<Key, Input, Output> {
    val deleteFun: (suspend (Key) -> Unit)? =
        if (delete != null) { key -> delete(key).await() } else null
    val deleteAllFun: (suspend () -> Unit)? = deleteAll?.let { { deleteAll().await() } }
    return from(
        reader = { key -> reader.invoke(key).asFlow() },
        writer = { key, output -> writer.invoke(key, output).await() },
        delete = deleteFun,
        deleteAll = deleteAllFun
    )
}
