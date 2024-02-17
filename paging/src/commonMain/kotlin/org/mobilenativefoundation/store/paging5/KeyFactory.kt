package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
interface KeyFactory<Id : Any, SK : StoreKey.Single<Id>> {
    fun createFor(id: Id): SK
}
