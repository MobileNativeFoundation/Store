package com.dropbox.external.store5

import com.dropbox.external.store5.impl.RealMarket
import kotlinx.coroutines.flow.Flow

/**
 * Integrates stores and a bookkeeper.
 * @see [RealMarket]
 * @see [Store]
 * @see [Bookkeeper]
 */
interface Market<Key : Any, Input : Any, Output : Any> {
    suspend fun read(reader: MarketReader<Key, Input, Output>): Flow<MarketResponse<Output>>
    suspend fun write(writer: MarketWriter<Key, Input, Output>): Boolean
    suspend fun delete(key: Key): Boolean
    suspend fun delete(): Boolean

    companion object {
        fun <Key : Any, Input : Any, Output : Any> of(
            stores: List<Store<Key, Input, Output>>,
            bookkeeper: Bookkeeper<Key>,
            fetcher: NetworkFetcher<Key, Input, Output>,
            updater: NetworkUpdater<Key, Input, Output>
        ): Market<Key, Input, Output> = RealMarket(stores, bookkeeper, fetcher, updater)
    }
}