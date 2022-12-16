package org.mobilenativefoundation.store.store5

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store.store5.impl.RealMarket

/**
 * Integrates stores and a bookkeeper.
 * @see [RealMarket]
 * @see [Store]
 * @see [Bookkeeper]
 */
interface Market<Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> {
    suspend fun read(reader: ReadRequest<Key, CommonRepresentation>): Flow<MarketResponse<CommonRepresentation>>
    suspend fun write(writer: WriteRequest<Key, CommonRepresentation>): NetworkWriteResponse
    suspend fun delete(key: Key): Boolean
    suspend fun delete(): Boolean

    companion object {
        fun <Key : Any, NetworkRepresentation : Any, CommonRepresentation : Any, NetworkWriteResponse : Any> of(
            stores: List<Store<Key, *, CommonRepresentation>>,
            bookkeeper: Bookkeeper<Key>,
            fetcher: NetworkFetcher<Key, CommonRepresentation, NetworkRepresentation, NetworkWriteResponse>,
            updater: NetworkUpdater<Key, CommonRepresentation, NetworkWriteResponse>
        ): Market<Key, NetworkRepresentation, CommonRepresentation, NetworkWriteResponse> = RealMarket(stores, bookkeeper, fetcher, updater)
    }
}
