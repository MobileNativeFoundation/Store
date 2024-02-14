package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
interface QueueManager<Id : Comparable<Id>, CK : StoreKey.Collection<Id>> {
    fun enqueue(key: CK)
}
