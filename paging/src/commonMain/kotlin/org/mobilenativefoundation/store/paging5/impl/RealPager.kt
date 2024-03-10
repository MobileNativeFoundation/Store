package org.mobilenativefoundation.store.paging5.impl

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.AppDispatcher
import org.mobilenativefoundation.store.paging5.Pager
import org.mobilenativefoundation.store.paging5.PagingAction
import org.mobilenativefoundation.store.paging5.PagingStateManager

@ExperimentalStoreApi
class RealPager<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CA : Any, CE : Any>(
    private val appDispatcher: AppDispatcher,
    pagingStateManager: PagingStateManager<Id, CK, SO, CE>,
) : Pager<Id, CK, SO, CA, CE> {
    init {
        appDispatcher.dispatch(PagingAction.App.Start)
    }

    override val state = pagingStateManager.state

    override fun dispatch(action: PagingAction.User) {
        appDispatcher.dispatch(action)
    }
}
