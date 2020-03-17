package com.dropbox.store.rx2

import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.SourceOfTruth
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Single
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx2.await

/**
 * Creates a (Non Flow) [Single] source of truth that is accessible via [reader], [writer],
 * [delete], and [deleteAll].
 *
 * @see com.dropbox.android.external.store4.StoreBuilder.persister
 */
@FlowPreview
@ExperimentalCoroutinesApi
fun <Key : Any, Input : Any, Output : Any> SourceOfTruth.Companion.fromMaybe(
    reader: (Key) -> Maybe<Output>,
    writer: (Key, Input) -> Completable,
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
fun <Key : Any, Input : Any, Output : Any> SourceOfTruth.Companion.fromFlowable(
    reader: (Key) -> Flowable<Output>,
    writer: (Key, Input) -> Completable,
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
