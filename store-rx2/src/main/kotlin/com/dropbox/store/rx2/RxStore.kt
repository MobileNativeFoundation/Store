package com.dropbox.store.rx2

import com.dropbox.android.external.store4.BuilderWithSourceOfTruth
import com.dropbox.android.external.store4.ExperimentalStoreApi
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreBuilder.Companion.from
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Scheduler
import io.reactivex.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx2.asCoroutineDispatcher
import kotlinx.coroutines.rx2.asFlowable
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.rx2.rxSingle

/**
 * Return a flow for the given key
 * @param request - see [StoreRequest] for configurations
 */
fun <Key : Any, Output : Any> Store<Key, Output>.observe(request: StoreRequest<Key>): Flowable<StoreResponse<Output>> =
    stream(request).asFlowable()

/**
 * Purge a particular entry from memory and disk cache.
 * Persistent storage will only be cleared if a delete function was passed to
 * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
 */
fun <Key : Any, Output : Any> Store<Key, Output>.observeClear(key: Key): Completable =
    rxSingle { clear(key) }

/**
 * Purge all entries from memory and disk cache.
 * Persistent storage will only be cleared if a deleteAll function was passed to
 * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
 */
@ExperimentalStoreApi
fun <Key : Any, Output : Any> Store<Key, Output>.observeClearAll(): Single<Unit> =
    rxSingle { clearAll() }

/**
 * Creates a new [StoreBuilder] from a [Flowable] fetcher.
 *
 * Use when creating a [Store] that fetches objects in an websocket-like multiple responses
 * per request protocol.
 *
 * @param fetcher a function for fetching a flow of network records.
 */
fun <Key : Any, Output : Any> rxFlowableStore(
    fetcher: (key: Key) -> Flowable<Output>
): StoreBuilder<Key, Output> = from { key: Key ->
    fetcher.invoke(key).asFlow()
}

/**
 * Creates a new [StoreBuilder] from a [Single] fetcher.
 *
 * Use when creating a [Store] that fetches objects from a [Single] source that emits one response
 *
 * @param fetcher a function for fetching a [Single] network response for a [Key]
 */
fun <Key : Any, Output : Any> rxSingleStore(
    fetcher: (key: Key) -> Single<Output>
): StoreBuilder<Key, Output> =
    from { key: Key ->
        fetcher.invoke(key).toFlowable().asFlow()

    }

/**
 * Define what scheduler fetcher requests will be called on,
 * if a scheduler is not set Store will use [GlobalScope]
 */
fun <Key : Any, Output : Any> StoreBuilder<Key, Output>.withScheduler(
    scheduler: Scheduler
): StoreBuilder<Key, Output> {
    return scope(CoroutineScope(scheduler.asCoroutineDispatcher()))
}

/**
 * Connects a (Non Flow) [Single] source of truth that is accessible via [reader], [writer],
 * [delete], and [deleteAll].
 *
 * @see persister
 */
fun <Key : Any, Output : Any, NewOutput : Any> StoreBuilder<Key, Output>.withSinglePersister(
    reader: (Key) -> Maybe<NewOutput>,
    writer: (Key, Output) -> Single<Unit>,
    delete: ((Key) -> Completable)? = null,
    deleteAll: (() -> Completable)? = null
): BuilderWithSourceOfTruth<Key, Output, NewOutput> {
    return nonFlowingPersister(
        reader = { key -> reader.invoke(key).await() },
        writer = { key, output -> writer.invoke(key, output).await() },
        delete = delete?.let { { key -> delete(key).await() } },
        deleteAll = { deleteAll?.invoke()?.await() }
    ) as BuilderWithSourceOfTruth<Key, Output, NewOutput>
}

/**
 * Connects a ([Flowable]) source of truth that is accessed via [reader], [writer] and [delete].
 *
 * For maximal flexibility, [writer]'s record type ([Output]] and [reader]'s record type
 * ([NewOutput]) are not identical. This allows us to read one type of objects from network and
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
fun <Key : Any, Output : Any, NewOutput : Any> StoreBuilder<Key, Output>.withFlowablePersister(
    reader: (Key) -> Flowable<NewOutput>,
    writer: (Key, Output) -> Single<Unit>,
    delete: ((Key) -> Completable)? = null,
    deleteAll: (() -> Completable)? = null
): BuilderWithSourceOfTruth<Key, Output, NewOutput> {
    return persister(
        reader = { key -> reader.invoke(key).asFlow() },
        writer = { key, output -> writer.invoke(key, output).await() },
        delete = { key -> delete?.invoke(key)?.await() },
        deleteAll = { deleteAll?.invoke()?.await() }
    ) as BuilderWithSourceOfTruth<Key, Output, NewOutput>
}
