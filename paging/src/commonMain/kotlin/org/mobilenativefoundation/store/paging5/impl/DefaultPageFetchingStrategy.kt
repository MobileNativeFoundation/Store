package org.mobilenativefoundation.store.paging5.impl

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.PageFetchingStrategy
import org.mobilenativefoundation.store.paging5.PagingState

@ExperimentalStoreApi
class DefaultPageFetchingStrategy<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> :
    PageFetchingStrategy<Id, CK, SO> {

    override fun shouldFetchNextPage(state: PagingState<Id, CK, SO>): Boolean {

        if (state.prefetchPosition == null) return true

        val orderedPagingData = state.pages.entries.flatMap { (_, page) ->
            page.data
        }

        val indexOfAnchor =
            if (state.anchorPosition == null) -1 else orderedPagingData.indexOfFirst { it.id == state.anchorPosition }
        val indexOfPrefetch = orderedPagingData.indexOfFirst { it.id == state.prefetchPosition }

        if (indexOfAnchor == -1 && indexOfPrefetch == -1 || indexOfPrefetch == -1) return true
        return indexOfPrefetch - indexOfAnchor < state.config.prefetchDistance
    }
}