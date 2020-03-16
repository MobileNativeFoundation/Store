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
