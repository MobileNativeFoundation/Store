package org.mobilenativefoundation.store.rx2

import io.reactivex.Completable
import io.reactivex.Flowable
import kotlinx.coroutines.rx2.asFlowable
import kotlinx.coroutines.rx2.rxCompletable
import kotlinx.coroutines.rx2.rxSingle
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreBuilder
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.impl.extensions.fresh
import org.mobilenativefoundation.store.store5.impl.extensions.get

/**
 * Return a [Flowable] for the given key
 * @param request - see [StoreReadRequest] for configurations
 */
fun <Key : Any, Output : Any> Store<Key, Output>.observe(request: StoreReadRequest<Key>): Flowable<StoreReadResponse<Output>> =
    stream(request).asFlowable()

/**
 * Purge a particular entry from memory and disk cache.
 * Persistent storage will only be cleared if a delete function was passed to
 * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
 */
fun <Key : Any, Output : Any> Store<Key, Output>.observeClear(key: Key): Completable = rxCompletable { clear(key) }

/**
 * Purge all entries from memory and disk cache.
 * Persistent storage will only be cleared if a deleteAll function was passed to
 * [StoreBuilder.persister] or [StoreBuilder.nonFlowingPersister] when creating the [Store].
 */
@ExperimentalStoreApi
fun <Key : Any, Output : Any> Store<Key, Output>.observeClearAll(): Completable = rxCompletable { clear() }

/**
 * Helper factory that will return data as a [Single] for [key] if it is cached otherwise will return fresh/network data (updating your caches)
 */
fun <Key : Any, Output : Any> Store<Key, Output>.getSingle(key: Key) = rxSingle { this@getSingle.get(key) }

/**
 * Helper factory that will return fresh data as a [Single] for [key] while updating your caches
 */
fun <Key : Any, Output : Any> Store<Key, Output>.freshSingle(key: Key) = rxSingle { this@freshSingle.fresh(key) }
