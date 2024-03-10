package org.mobilenativefoundation.store.paging5.impl

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.paging5.AppDispatcher
import org.mobilenativefoundation.store.paging5.Dispatcher
import org.mobilenativefoundation.store.paging5.PagingAction

@ExperimentalStoreApi
class RealAppDispatcher(
    private val delegate: Dispatcher,
) : AppDispatcher {
    override fun dispatch(action: PagingAction) {
        delegate.dispatch(action)
    }
}
