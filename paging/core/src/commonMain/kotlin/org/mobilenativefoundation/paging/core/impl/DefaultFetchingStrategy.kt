package org.mobilenativefoundation.paging.core.impl

import org.mobilenativefoundation.paging.core.FetchingStrategy
import org.mobilenativefoundation.paging.core.PagingBuffer
import org.mobilenativefoundation.paging.core.PagingConfig
import org.mobilenativefoundation.paging.core.PagingKey
import kotlin.math.max

class DefaultFetchingStrategy<Id : Comparable<Id>, K : Any, P : Any, D : Any> : FetchingStrategy<Id, K, P, D> {
    override fun shouldFetch(anchorPosition: PagingKey<K, P>, prefetchPosition: PagingKey<K, P>?, pagingConfig: PagingConfig, pagingBuffer: PagingBuffer<Id, K, P, D>): Boolean {
        if (prefetchPosition == null) return true

        val indexOfAnchor = pagingBuffer.indexOf(anchorPosition)
        val indexOfPrefetch = pagingBuffer.indexOf(prefetchPosition)

        if ((indexOfAnchor == -1 && indexOfPrefetch == -1) || indexOfPrefetch == -1) return true

        val effectiveAnchor = max(indexOfAnchor, 0)
        val effectivePrefetch = (indexOfPrefetch + 1) * pagingConfig.pageSize

        val shouldFetch = effectivePrefetch - effectiveAnchor < pagingConfig.prefetchDistance

        return shouldFetch
    }

}
