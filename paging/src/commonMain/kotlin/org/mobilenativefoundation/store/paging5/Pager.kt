package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.store5.MutableStore
import org.mobilenativefoundation.store.store5.Store
import org.mobilenativefoundation.store.store5.StoreReadRequest
import org.mobilenativefoundation.store.store5.StoreReadResponse

@ExperimentalStoreApi
interface Pager<Id : Any, K : StoreKey<Id>, SO : StoreData.Single<Id>> {
    val state: StateFlow<PagingData<Id, SO>>
    fun load(key: K)

    companion object {
        fun <Id : Any, SK : StoreKey.Single<Id>, K : StoreKey<Id>, SO : StoreData.Single<Id>, O : StoreData<Id>> create(
            scope: CoroutineScope,
            store: Store<K, O>,
            joiner: Joiner<Id, K, SO>,
            keyFactory: KeyFactory<Id, SK>
        ): Pager<Id, K, SO> {

            val streamer = object : Streamer<Id, K, O> {
                override fun invoke(key: K): Flow<StoreReadResponse<O>> {
                    return store.stream(StoreReadRequest.fresh(key))
                }
            }

            return RealPager(
                scope,
                streamer,
                joiner,
                keyFactory
            )
        }

        fun <Id : Any, SK : StoreKey.Single<Id>, K : StoreKey<Id>, SO : StoreData.Single<Id>, O : StoreData<Id>> create(
            scope: CoroutineScope,
            store: MutableStore<K, O>,
            joiner: Joiner<Id, K, SO>,
            keyFactory: KeyFactory<Id, SK>
        ): Pager<Id, K, SO> {

            val streamer = object : Streamer<Id, K, O> {
                override fun invoke(key: K): Flow<StoreReadResponse<O>> {
                    return store.stream<Any>(StoreReadRequest.fresh(key))
                }
            }

            return RealPager(
                scope,
                streamer,
                joiner,
                keyFactory
            )
        }
    }
}