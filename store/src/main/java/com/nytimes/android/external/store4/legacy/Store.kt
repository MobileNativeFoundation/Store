package com.nytimes.android.external.store4.legacy

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * A [Store] can
 * [Store.get() ][Store.get] cached data or
 * force a call to [Store.fresh() ][Store.fresh]
 * (skipping cache)
 */
interface Store<T, V> {

    /**
     * Return an Observable of T for request Barcode
     * Data will be returned from oldest non expired source
     * Sources are Memory Cache, Disk Cache, Inflight, Network Response
     */
    suspend fun get(key: V): T

    /**
     * Return an Observable of T for requested Barcode skipping Memory & Disk Cache
     */
    suspend fun fresh(key: V): T

    /**
     * @return an Observable that emits "fresh" new response from the store that hit the fetcher
     * WARNING: stream is an endless observable, be careful when combining
     * with operators that expect an OnComplete event
     */
    fun stream(): Flow<Pair<V, T>>

    /**
     * Similar to  [Store.get() ][Store.get]
     * Rather than returning a single response,
     * Stream will stay subscribed for future emissions to the Store
     * Errors will be dropped
     *
     */
    fun stream(key: V): Flow<T> =
            stream().filter { it.first == key }.map { (_, value) -> value }

    /**
     * Purge a particular entry from memory and disk cache.
     * Persister will only be cleared if they implements Clearable
     */
    suspend fun clear(key: V)

}
