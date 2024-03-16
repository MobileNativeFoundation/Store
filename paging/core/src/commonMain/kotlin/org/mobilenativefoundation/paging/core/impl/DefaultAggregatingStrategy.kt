package org.mobilenativefoundation.paging.core.impl

import org.mobilenativefoundation.paging.core.AggregatingStrategy
import org.mobilenativefoundation.paging.core.PagingBuffer
import org.mobilenativefoundation.paging.core.PagingConfig
import org.mobilenativefoundation.paging.core.PagingConfig.InsertionStrategy
import org.mobilenativefoundation.paging.core.PagingData
import org.mobilenativefoundation.paging.core.PagingItems
import org.mobilenativefoundation.paging.core.PagingKey
import org.mobilenativefoundation.paging.core.PagingSource

class DefaultAggregatingStrategy<Id : Comparable<Id>, K : Any, P : Any, D : Any> : AggregatingStrategy<Id, K, P, D> {
    override fun aggregate(anchorPosition: PagingKey<K, P>, prefetchPosition: PagingKey<K, P>?, pagingConfig: PagingConfig, pagingBuffer: PagingBuffer<Id, K, P, D>): PagingItems<Id, K, P, D> {
        if (pagingBuffer.isEmpty()) return PagingItems(emptyList())

        val orderedItems = mutableListOf<PagingData.Single<Id, K, P, D>>()

        var currentPage: PagingSource.LoadResult.Data<Id, K, P, D>? = pagingBuffer.head()

        while (currentPage != null) {
            when (pagingConfig.insertionStrategy) {
                InsertionStrategy.APPEND -> orderedItems.addAll(currentPage.collection.items)
                InsertionStrategy.PREPEND -> orderedItems.addAll(0, currentPage.collection.items)
                InsertionStrategy.REPLACE -> {
                    orderedItems.clear()
                    orderedItems.addAll(currentPage.collection.items)
                }
            }

            currentPage = currentPage.collection.nextKey?.let { pagingBuffer.get(it) }
        }

        return PagingItems(orderedItems)
    }
}