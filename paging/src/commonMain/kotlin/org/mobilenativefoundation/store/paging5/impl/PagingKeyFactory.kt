package org.mobilenativefoundation.store.paging5.impl

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
interface PagingKeyFactory<Id : Any, SK : StoreKey.Single<Id>, SO : StoreData.Single<Id>> {
    fun createKeyFor(data: SO): SK
}