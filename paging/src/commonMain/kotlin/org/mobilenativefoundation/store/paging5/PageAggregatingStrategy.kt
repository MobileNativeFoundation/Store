package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
fun interface PageAggregatingStrategy<Id : Any, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> {
    fun aggregate(state: PagingState<Id, CK, SO>): Pager.PagingData<Id, SO>
}