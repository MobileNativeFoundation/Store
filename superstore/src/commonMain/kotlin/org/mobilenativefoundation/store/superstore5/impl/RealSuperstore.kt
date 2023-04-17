package org.mobilenativefoundation.store.superstore5.impl

import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin
import org.mobilenativefoundation.store.superstore5.Superstore
import org.mobilenativefoundation.store.superstore5.SuperstoreResponse
import org.mobilenativefoundation.store.superstore5.SuperstoreResponseOrigin
import org.mobilenativefoundation.store.superstore5.Warehouse
import org.mobilenativefoundation.store.superstore5.WarehouseResponse

/**
 * Coordinates [Store] and [Warehouse].
 * Tries to fetch data through [Store].
 * On failure, fetches data using [Warehouse] until a success.
 */
class RealSuperstore<Key : Any, Output : Any>(
    private val store: Store<Key, Output>,
    private val warehouses: List<Warehouse<Key, Output>>,
) : Superstore<Key, Output> {
    override fun stream(request: StoreReadRequest<Key>): Flow<SuperstoreResponse<Output>> {

        return channelFlow {
            store.stream(request).collect {
                try {
                    when (it) {
                        is StoreReadResponse.Data -> useStore(
                            it.requireData(),
                            it.origin.toSuperstoreResponseOrigin()
                        )

                        is StoreReadResponse.Loading -> send(SuperstoreResponse.Loading)
                        is StoreReadResponse.NoNewData -> send(SuperstoreResponse.NoNewData)
                        is StoreReadResponse.Error.Exception -> useWarehouse(request.key)
                        is StoreReadResponse.Error.Message -> useWarehouse(request.key)
                    }
                } catch (error: Throwable) {
                    useWarehouse(request.key)
                }
            }
        }
    }

    private suspend fun List<Warehouse<Key, Output>>.search(key: Key): WarehouseResponse<Output> {
        for (warehouse in this) {
            try {
                val result = warehouse.get(key)
                if (result is WarehouseResponse.Data) {
                    return result
                }
            } catch (_: Throwable) {
            }
        }
        throw Throwable(message = "Searched all warehouses. None have a value for: $key.")
    }

    private suspend fun ProducerScope<SuperstoreResponse<Output>>.useWarehouse(key: Key) {

        val warehouseData = warehouses.search(key) as WarehouseResponse.Data<Output>

        send(
            SuperstoreResponse.Data(
                data = warehouseData.data,
                origin = SuperstoreResponseOrigin.Warehouse(warehouseData.origin),
            ),
        )
    }

    private suspend fun ProducerScope<SuperstoreResponse<Output>>.useStore(
        output: Output,
        origin: SuperstoreResponseOrigin
    ) {
        send(SuperstoreResponse.Data(output, origin))
    }

    private fun StoreReadResponseOrigin.toSuperstoreResponseOrigin() = when (this) {
        StoreReadResponseOrigin.Cache -> SuperstoreResponseOrigin.Cache
        StoreReadResponseOrigin.SourceOfTruth -> SuperstoreResponseOrigin.SourceOfTruth
        StoreReadResponseOrigin.Fetcher -> SuperstoreResponseOrigin.Fetcher
    }
}
