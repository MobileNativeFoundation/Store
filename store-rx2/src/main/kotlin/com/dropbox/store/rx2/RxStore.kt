package com.dropbox.store.rx2

import com.dropbox.android.external.store4.ExperimentalStoreApi
import com.dropbox.android.external.store4.Store
import com.dropbox.android.external.store4.StoreBuilder
import com.dropbox.android.external.store4.StoreRequest
import com.dropbox.android.external.store4.StoreResponse
import com.dropbox.android.external.store4.fresh
import com.dropbox.android.external.store4.get
import io.reactivex.Completable
import io.reactivex.Flowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.rx2.asFlowable
import kotlinx.coroutines.rx2.rxCompletable
import kotlinx.coroutines.rx2.rxSingle

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
 * Helper factory that will return data as a [Single] for [key] if it is cached otherwise will return fresh/network data (updating your caches)
 */
fun <Key : Any, Output : Any> Store<Key, Output>.getSingle(key: Key) = rxSingle { this@getSingle.get(key) }

/**
 * Helper factory that will return fresh data as a [Single] for [key] while updating your caches
 */
fun <Key : Any, Output : Any> Store<Key, Output>.freshSingle(key: Key) = rxSingle { this@freshSingle.fresh(key) }
