package com.dropbox.store.rx2

import com.dropbox.android.external.store4.SourceOfTruth
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx2.await

/**
 * Creates a [Maybe] source of truth that is accessible via [reader], [writer], [delete] and
 * [deleteAll].
 *
 * @param reader function for reading records from the source of truth
 * @param writer function for writing updates to the backing source of truth
 * @param delete function for deleting records in the source of truth for the given key
 * @param deleteAll function for deleting all records in the source of truth
 *
 */
fun <Key : Any, Input : Any, Output : Any> SourceOfTruth.Companion.fromMaybe(
    reader: (Key) -> Maybe<Output>,
    writer: (Key, Input) -> Completable,
    delete: ((Key) -> Completable)? = null,
    deleteAll: (() -> Completable)? = null
): SourceOfTruth<Key, Input, Output> {
    val deleteFun: (suspend (Key) -> Unit)? =
        if (delete != null) { key -> delete(key).await() } else null
    val deleteAllFun: (suspend () -> Unit)? = deleteAll?.let { { deleteAll().await() } }
    return of(
        reader = { key -> reader.invoke(key).await() },
        writer = { key, output -> writer.invoke(key, output).await() },
        delete = deleteFun,
        deleteAll = deleteAllFun
    )
}

/**
 * Creates a ([Flowable]) source of truth that is accessed via [reader], [writer], [delete] and
 * [deleteAll].
 *
 * @param reader function for reading records from the source of truth
 * @param writer function for writing updates to the backing source of truth
 * @param delete function for deleting records in the source of truth for the given key
 * @param deleteAll function for deleting all records in the source of truth
 *
 */
fun <Key : Any, Input : Any, Output : Any> SourceOfTruth.Companion.ofFlowable(
    reader: (Key) -> Flowable<Output>,
    writer: (Key, Input) -> Completable,
    delete: ((Key) -> Completable)? = null,
    deleteAll: (() -> Completable)? = null
): SourceOfTruth<Key, Input, Output> {
    val deleteFun: (suspend (Key) -> Unit)? =
        if (delete != null) { key -> delete(key).await() } else null
    val deleteAllFun: (suspend () -> Unit)? = deleteAll?.let { { deleteAll().await() } }
    return of(
        flowReader = { key -> reader.invoke(key).asFlow() },
        writer = { key, output -> writer.invoke(key, output).await() },
        delete = deleteFun,
        deleteAll = deleteAllFun
    )
}
