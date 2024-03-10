package org.mobilenativefoundation.store.paging5

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey

@ExperimentalStoreApi
fun interface PageFetchingStrategy<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> {
    fun shouldFetchNextPage(
        anchorPosition: Id?,
        prefetchPosition: Id?,
        pagingConfig: PagingConfig,
        pagingBuffer: PagingBuffer<Id, CK, SO>,
    ): Boolean
}
