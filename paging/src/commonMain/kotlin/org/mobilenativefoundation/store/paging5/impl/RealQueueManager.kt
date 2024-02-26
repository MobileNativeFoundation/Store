package org.mobilenativefoundation.store.paging5.impl

import kotlinx.coroutines.flow.StateFlow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.PageFetchingStrategy
import org.mobilenativefoundation.store.paging5.PagingState

@ExperimentalStoreApi
internal class RealQueueManager<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>>(
    private val state: StateFlow<PagingState<Id, CK, SO>>,
    private val pageFetchingStrategy: PageFetchingStrategy<Id, CK, SO>,
    private val loadPage: (key: CK) -> Unit
) : QueueManager<Id, CK> {
    private val queue: ArrayDeque<CK> = ArrayDeque()

    override fun enqueue(key: CK) {
        queue.addLast(key)
        processQueue()
    }

    private fun processQueue() {
        while (queue.isNotEmpty() && pageFetchingStrategy.shouldFetchNextPage(state.value)) {
            loadPage(queue.removeFirst())
        }
    }
}