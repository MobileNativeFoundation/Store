package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi

@ExperimentalStoreApi
interface Dispatcher {
    fun <A : PagingAction> dispatch(action: A, index: Int = 0)
}
