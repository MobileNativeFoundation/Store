package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData

@ExperimentalStoreApi
data class PagingData<Id : Any, SO : StoreData.Single<Id>>(
    val items: List<SO>
)
