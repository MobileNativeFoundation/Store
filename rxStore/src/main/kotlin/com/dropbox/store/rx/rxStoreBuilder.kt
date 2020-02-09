package com.dropbox.store.rx

import com.dropbox.android.external.store4.BuilderImpl
import com.dropbox.android.external.store4.BuilderWithSourceOfTruth
import com.dropbox.android.external.store4.ExperimentalStoreApi
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Single
import kotlinx.coroutines.reactive.asFlow
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
fun <Key : Any, Output : Any> Store<Key, Output>.observeClear(key: Key): Single<Unit> =
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
 fun <Key : Any,  Output : Any> rxFlowStore(
     fetcher: (key: Key) -> Flowable<Output>
): StoreBuilder<Key, Output> = BuilderImpl { key: Key ->
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
): BuilderImpl<Key, Output> =
    BuilderImpl { key: Key ->
        fetcher.invoke(key).toFlowable().asFlow()
    }


fun <Key : Any, Output : Any, NewOutput : Any> BuilderImpl<Key, Output>.withSinglePersister(
    reader: (Key) -> Maybe<NewOutput>,
    writer: (Key, Output) -> Single<Unit>,
    delete: ((Key) -> Unit)? = null,
    deleteAll: (() -> Unit)? = null
): BuilderWithSourceOfTruth<Key, Output, NewOutput> {
    return nonFlowingPersister(
        reader = { key -> reader.invoke(key).await() },
        writer = { key, output -> writer.invoke(key, output).await() },
        delete = { key -> delete?.invoke(key) },
        deleteAll = { deleteAll?.invoke() }
    )
}

fun <Key : Any, Output : Any, NewOutput : Any> BuilderImpl<Key, Output>.withFlowablePersister(
    reader: (Key) -> Flowable<NewOutput>,
    writer: (Key, Output) -> Single<Unit>,
    delete: ((Key) -> Unit)? = null,
    deleteAll: (() -> Unit)? = null
) {
    persister(
        reader = { key -> reader.invoke(key).asFlow() },
        writer = { key, output -> writer.invoke(key, output).await() },
        delete = { key -> delete?.invoke(key) },
        deleteAll = { deleteAll?.invoke() }
    )
}


