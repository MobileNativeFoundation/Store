package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.flow.StateFlow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
interface Pager<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CA : Any, CE : Any> {
    val state: StateFlow<PagingState<Id, CK, SO, CE>>
    fun dispatch(action: PagingAction.User)
}
