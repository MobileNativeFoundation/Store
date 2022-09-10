package com.dropbox.external.store5

import com.dropbox.external.store5.impl.RealMarket
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Integrates stores and a conflict resolution system.
 * @see [RealMarket]
 * @see [Store]
 * @see [ConflictResolver]
 */
interface Market<Key : Any> {
    suspend fun <Input : Any, Output : Any> read(reader: Reader<Key, Input, Output>): MutableSharedFlow<MarketResponse<Output>>
    suspend fun <Input : Any, Output : Any> write(writer: Writer<Key, Input, Output>): Boolean
    suspend fun delete(key: Key): Boolean
    suspend fun delete(): Boolean

    companion object {
        fun <Key : Any, Input : Any, Output : Any> of(
            stores: List<Store<Key, Input, Output>>,
            conflictResolver: ConflictResolver<Key, Input, Output>
        ) = RealMarket(stores, conflictResolver)
    }
}