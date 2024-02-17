package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
interface Joiner<Id : Any, K : StoreKey<Id>, SO : StoreData.Single<Id>> {
    suspend operator fun invoke(data: Map<K, PagingData<Id, SO>>): PagingData<Id, SO>
}
