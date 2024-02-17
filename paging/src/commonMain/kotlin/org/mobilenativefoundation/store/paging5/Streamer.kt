package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.flow.Flow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.store5.StoreReadResponse

@ExperimentalStoreApi
internal interface Streamer<Id : Any, K : StoreKey<Id>, O : StoreData<Id>> {
    operator fun invoke(key: K): Flow<StoreReadResponse<O>>
}
