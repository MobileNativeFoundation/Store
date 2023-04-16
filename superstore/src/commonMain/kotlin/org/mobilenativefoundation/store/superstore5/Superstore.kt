package org.mobilenativefoundation.store.superstore5

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.superstore5.impl.RealSuperstore

/**
 * Represents a [Store] with fallback mechanisms.
 */
interface Superstore<Key : Any, Output : Any> {
    fun get(
        key: Key,
        fresh: Boolean = false,
        refresh: Boolean = false
    ): Flow<SuperstoreResponse<Output>>

    companion object {

        /**
         * Creates a [Superstore] from a [Store] and list of [Warehouse].
         */
        fun <Key : Any, Output : Any> from(
            store: Store<Key, Output>,
            warehouses: List<Warehouse<Key, Output>>
        ): Superstore<Key, Output> = RealSuperstore(store, warehouses)
    }
}