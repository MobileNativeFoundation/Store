package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
interface CustomActionReducer<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CA : Any, CE : Any> {
    fun reduce(
        prevState: PagingState<Id, CK, SO, CE>,
        action: PagingAction.User.Custom<CA>
    ): PagingState<Id, CK, SO, CE>
}
