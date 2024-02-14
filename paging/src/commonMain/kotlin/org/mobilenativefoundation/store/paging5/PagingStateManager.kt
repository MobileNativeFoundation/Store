package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
class PagingStateManager<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CE : Any>(
    initialState: PagingState<Id, CK, SO, CE>,
) {
    private val _state = MutableStateFlow(initialState)
    val state = _state.asStateFlow()

    fun updateState(nextState: PagingState<Id, CK, SO, CE>) {
        _state.value = nextState
    }
}
