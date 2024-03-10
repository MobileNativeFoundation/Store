package org.mobilenativefoundation.store.paging5.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.DispatcherInjector
import org.mobilenativefoundation.store.paging5.Logger
import org.mobilenativefoundation.store.paging5.PageFetchingStrategy
import org.mobilenativefoundation.store.paging5.PagingAction
import org.mobilenativefoundation.store.paging5.PagingBuffer
import org.mobilenativefoundation.store.paging5.PagingConfig
import org.mobilenativefoundation.store.paging5.PagingStateManager
import org.mobilenativefoundation.store.paging5.QueueManager

@ExperimentalStoreApi
internal class DefaultQueueManager<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CE : Any>(
    private val pagingConfig: PagingConfig,
    private val anchorPosition: StateFlow<Id?>,
    private val pageFetchingStrategy: PageFetchingStrategy<Id, CK, SO>,
    private val dispatcherInjector: DispatcherInjector,
    private val pagingStateManager: PagingStateManager<Id, CK, SO, CE>,
    private val pagingBuffer: PagingBuffer<Id, CK, SO>,
    private val logger: Logger?,
    private val childScope: CoroutineScope,
) : QueueManager<Id, CK> {
    private val queue: ArrayDeque<CK> = ArrayDeque()

    override fun enqueue(key: CK) {
        logger?.d(
            """Enqueuing:
                Key: $key
            """.trimMargin(),
        )
        queue.addLast(key)
        processQueue()
    }

    private fun processQueue() {
        while (queue.isNotEmpty() &&
            pageFetchingStrategy.shouldFetchNextPage(
                anchorPosition = anchorPosition.value,
                prefetchPosition = pagingStateManager.state.value.prefetchPosition,
                pagingConfig = pagingConfig,
                pagingBuffer = pagingBuffer,
            )
        ) {
            val nextKey = queue.removeFirst()

            logger?.d(
                """Dequeued:
                    Key: $nextKey
                """.trimMargin(),
            )

            childScope.launch {
                when (nextKey) {
                    is StoreKey.Collection.Cursor<*> -> {
                        if (nextKey.cursor != null) {
                            dispatcherInjector.dispatch(PagingAction.App.Load(nextKey))
                        }
                    }

                    is StoreKey.Collection.Page -> {
                        dispatcherInjector.dispatch(PagingAction.App.Load(nextKey))
                    }
                }
            }
        }
    }
}
