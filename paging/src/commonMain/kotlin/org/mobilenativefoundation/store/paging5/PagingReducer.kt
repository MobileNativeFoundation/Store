package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
interface PagingReducer<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CE : Any> {
    fun reduce(
        state: PagingState<Id, CK, SO, CE>,
        action: PagingAction,
    ): PagingState<Id, CK, SO, CE>
}
