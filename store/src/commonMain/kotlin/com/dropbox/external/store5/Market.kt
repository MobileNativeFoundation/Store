package com.dropbox.external.store5

import com.dropbox.external.store5.impl.RealMarket
import kotlinx.coroutines.flow.Flow

/**
 * Integrates stores and a bookkeeper.
 * @see [RealMarket]
 * @see [Store]
 * @see [Bookkeeper]
 */
interface Market<Key : Any> {
    suspend fun <Input : Any, Output : Any> read(reader: Reader<Key, Input, Output>): Flow<MarketResponse<Output>>
    suspend fun <Input : Any, Output : Any> write(writer: Writer<Key, Input, Output>): Boolean
    suspend fun delete(key: Key): Boolean
    suspend fun delete(): Boolean

    companion object {
        fun <Key : Any, Input : Any, Output : Any> of(
            stores: List<Store<Key, Input, Output>>,
            bookkeeper: Bookkeeper<Key>
        ): Market<Key> = RealMarket(stores, bookkeeper)
    }
}