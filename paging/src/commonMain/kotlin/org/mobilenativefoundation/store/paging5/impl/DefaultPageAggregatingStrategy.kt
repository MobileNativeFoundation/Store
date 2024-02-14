package org.mobilenativefoundation.store.paging5.impl

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.InsertionStrategy
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.PageAggregatingStrategy
import org.mobilenativefoundation.store.paging5.PagingBuffer
import org.mobilenativefoundation.store.paging5.PagingConfig
import org.mobilenativefoundation.store.paging5.PagingItems
import org.mobilenativefoundation.store.paging5.PagingSource

@ExperimentalStoreApi
class DefaultPageAggregatingStrategy<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> :
    PageAggregatingStrategy<Id, CK, SO> {
    override fun aggregate(
        anchorPosition: Id?,
        prefetchPosition: Id?,
        pagingConfig: PagingConfig,
        pagingBuffer: PagingBuffer<Id, CK, SO>
    ): PagingItems<Id, SO> {
        if (pagingBuffer.isEmpty()) return PagingItems(emptyList())

        val orderedItems = mutableListOf<SO>()

        var currentPage: PagingSource.LoadResult.Page<Id, CK, SO>? = pagingBuffer.head()

        while (currentPage != null) {
            when (currentPage.prevKey.insertionStrategy) {
                InsertionStrategy.APPEND -> orderedItems.addAll(currentPage.data)
                InsertionStrategy.PREPEND -> orderedItems.addAll(0, currentPage.data)
                InsertionStrategy.REPLACE -> {
                    orderedItems.clear()
                    orderedItems.addAll(currentPage.data)
                }
            }

            currentPage = currentPage.nextKey?.let { pagingBuffer.get(it) }
        }

        return PagingItems(orderedItems)
    }
}
