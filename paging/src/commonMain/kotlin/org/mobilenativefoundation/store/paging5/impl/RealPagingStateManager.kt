package org.mobilenativefoundation.store.paging5.impl

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.paging5.Pager

@ExperimentalStoreApi
internal class RealPagingStateManager<Id : Comparable<Id>, SO : StoreData.Single<Id>> : PagingStateManager<Id, SO> {
    private val _data = MutableStateFlow(createInitialPagingData())
    private val _errors = MutableStateFlow<Pager.PagingError?>(null)

    override val data: StateFlow<Pager.PagingData<Id, SO>> = _data.asStateFlow()
    override val errors: StateFlow<Pager.PagingError?> = _errors.asStateFlow()

    override fun updateError(newError: Pager.PagingError?) {
        _errors.value = newError
    }

    override fun invalidateData() {
        _data.value = createInitialPagingData()
    }

    override fun clearError() {
        _errors.value = null
    }

    override fun updateData(newData: Pager.PagingData<Id, SO>) {
        _data.value = newData
    }

    private fun createInitialPagingData() = Pager.PagingData<Id, SO>(emptyList())

}