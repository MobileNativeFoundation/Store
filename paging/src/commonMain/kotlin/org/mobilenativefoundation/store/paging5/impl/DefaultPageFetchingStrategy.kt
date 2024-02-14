package org.mobilenativefoundation.store.paging5.impl

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.PageFetchingStrategy
import org.mobilenativefoundation.store.paging5.PagingBuffer
import org.mobilenativefoundation.store.paging5.PagingConfig

@ExperimentalStoreApi
class DefaultPageFetchingStrategy<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> :
    PageFetchingStrategy<Id, CK, SO> {

    override fun shouldFetchNextPage(
        anchorPosition: Id?,
        prefetchPosition: Id?,
        pagingConfig: PagingConfig,
        pagingBuffer: PagingBuffer<Id, CK, SO>
    ): Boolean {
        if (prefetchPosition == null) return true

        val indexOfAnchor = if (anchorPosition != null) pagingBuffer.indexOf(anchorPosition) else -1
        val indexOfPrefetch = pagingBuffer.indexOf(prefetchPosition)

        if (indexOfAnchor == -1 && indexOfPrefetch == -1 || indexOfPrefetch == -1) return true
        return indexOfPrefetch - indexOfAnchor < pagingConfig.prefetchDistance
    }
}
