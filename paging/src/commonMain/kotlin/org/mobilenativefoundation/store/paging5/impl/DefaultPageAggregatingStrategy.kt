package org.mobilenativefoundation.store.paging5.impl

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.InsertionStrategy
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.PageAggregatingStrategy
import org.mobilenativefoundation.store.paging5.Pager
import org.mobilenativefoundation.store.paging5.PagingSource
import org.mobilenativefoundation.store.paging5.PagingState

@ExperimentalStoreApi
class DefaultPageAggregatingStrategy<Id : Any, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> :
    PageAggregatingStrategy<Id, CK, SO> {
    override fun aggregate(state: PagingState<Id, CK, SO>): Pager.PagingData<Id, SO> {
        if (state.pages.isEmpty()) return Pager.PagingData(emptyList())

        val orderedItems = mutableListOf<SO>()

        val keyToPage = state.pages.values.associateBy { it.prevKey }

        var currentPage: PagingSource.LoadResult.Page<Id, CK, SO>? = state.pages.values.first()

        while (currentPage != null) {
            when (currentPage.prevKey?.insertionStrategy) {
                InsertionStrategy.APPEND -> orderedItems.addAll(currentPage.data)
                InsertionStrategy.PREPEND -> orderedItems.addAll(0, currentPage.data)
                null, InsertionStrategy.REPLACE -> {
                    orderedItems.clear()
                    orderedItems.addAll(currentPage.data)
                }
            }

            currentPage = currentPage.nextKey?.let { keyToPage[it] }
        }

        return Pager.PagingData(orderedItems)
    }
}