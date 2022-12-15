package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store.store5.impl.RealMarket

/**
 * Integrates stores and a bookkeeper.
 * @see [RealMarket]
 * @see [Store]
 * @see [Bookkeeper]
 */
interface Market<Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any> {
    suspend fun read(reader: ReadRequest<Key, NetworkRepresentation, CommonRepresentation>): Flow<MarketResponse<CommonRepresentation>>
    suspend fun write(writer: WriteRequest<Key, NetworkRepresentation, CommonRepresentation>): Boolean
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
