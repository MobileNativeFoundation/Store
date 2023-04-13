package org.mobilenativefoundation.store.superstore5


import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse
import org.mobilenativefoundation.store.store5.StoreReadResponseOrigin

/**
 * Coordinates [Store] and [Warehouse].
 * Tries to fetch data through [Store].
 * On failure, fetches data using [Warehouse] until a success.
 */
class RealSuperstore<Key : Any, Output : Any>(
    private val store: Store<Key, Output>,
    private val warehouses: List<Warehouse<Key, Output>>,
) : Superstore<Key, Output> {
    override fun get(key: Key, fresh: Boolean, refresh: Boolean): Flow<SuperstoreResponse<Output>> {
        val request =
            if (fresh) StoreReadRequest.fresh(key) else StoreReadRequest.cached(key, refresh)
        return flow {
            store.stream(request).collect {
                try {
                    when (it) {
                        is StoreReadResponse.Data -> useStore(
                            it.requireData(),
                            it.origin.toSuperstoreResponseOrigin()
                        )

                        is StoreReadResponse.Loading -> emit(SuperstoreResponse.Loading)
                        is StoreReadResponse.NoNewData -> {}
                        is StoreReadResponse.Error.Exception -> useWarehouse(key)
                        is StoreReadResponse.Error.Message -> useWarehouse(key)
                    }
                } catch (error: Throwable) {
                    useWarehouse(key)
                }
            }
        }
    }

    private suspend fun List<Warehouse<Key, Output>>.search(key: Key): Output {
        for (warehouse in this) {
            val result = warehouse.get(key)
            if (result != null) {
                return result
            }
        }
        throw Throwable(message = "Searched all warehouses. None have a value for: $key.")
    }

    private suspend fun FlowCollector<SuperstoreResponse<Output>>.useWarehouse(key: Key) {

        val data = warehouses.search(key)

        emit(
            SuperstoreResponse.Data(
                data = data,
                origin = SuperstoreResponseOrigin.Warehouse,
            ),
        )
    }

    private suspend fun FlowCollector<SuperstoreResponse<Output>>.useStore(
        output: Output,
        origin: SuperstoreResponseOrigin
    ) {
        emit(SuperstoreResponse.Data(output, origin))
    }

    private fun StoreReadResponseOrigin.toSuperstoreResponseOrigin() = when (this) {
        StoreReadResponseOrigin.Cache -> SuperstoreResponseOrigin.Cache
        StoreReadResponseOrigin.SourceOfTruth -> SuperstoreResponseOrigin.SourceOfTruth
        StoreReadResponseOrigin.Fetcher -> SuperstoreResponseOrigin.Fetcher
    }
}
