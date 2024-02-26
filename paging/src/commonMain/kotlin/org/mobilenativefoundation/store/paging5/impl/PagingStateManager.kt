package org.mobilenativefoundation.store.paging5.impl

import kotlinx.coroutines.flow.StateFlow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.paging5.Pager

@ExperimentalStoreApi
internal interface PagingStateManager<Id : Comparable<Id>, SO : StoreData.Single<Id>> {
    val data: StateFlow<Pager.PagingData<Id, SO>>
    val errors: StateFlow<Pager.PagingError?>

    fun updateData(newData: Pager.PagingData<Id, SO>)
    fun updateError(newError: Pager.PagingError?)

    fun invalidateData()
    fun clearError()
}