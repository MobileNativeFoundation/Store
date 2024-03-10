package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.impl.DefaultPageAggregatingStrategy
import org.mobilenativefoundation.store.paging5.impl.DefaultPageFetchingStrategy
import org.mobilenativefoundation.store.paging5.impl.DefaultQueueManager
import org.mobilenativefoundation.store.paging5.impl.DefaultReducer
import org.mobilenativefoundation.store.paging5.impl.RetriesRepository

@ExperimentalStoreApi
class DefaultReducerBuilder<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CA : Any, CE : Any>(
    private val initialKey: CK,
    private val childScope: CoroutineScope,
    private val anchorPosition: StateFlow<Id>,
    private val pagingConfig: PagingConfig,
    private val dispatcherInjector: DispatcherInjector,
    private val pagingStateManager: PagingStateManager<Id, CK, SO, CE>,
    private val logger: Logger?,
) {
    private var errorHandlingStrategy: ErrorHandlingStrategy = ErrorHandlingStrategy.PassThrough
    private var aggregatingStrategy: PageAggregatingStrategy<Id, CK, SO> = DefaultPageAggregatingStrategy()
    private var pageFetchingStrategy: PageFetchingStrategy<Id, CK, SO> = DefaultPageFetchingStrategy()
    private var customActionReducer: CustomActionReducer<Id, CK, SO, CA, CE>? = null
    private var pagingBufferMaxSize: Int = 100

    fun errorHandlingStrategy(errorHandlingStrategy: ErrorHandlingStrategy) = apply { this.errorHandlingStrategy = errorHandlingStrategy }

    fun aggregatingStrategy(aggregatingStrategy: PageAggregatingStrategy<Id, CK, SO>) =
        apply { this.aggregatingStrategy = aggregatingStrategy }

    fun pageFetchingStrategy(pageFetchingStrategy: PageFetchingStrategy<Id, CK, SO>) =
        apply { this.pageFetchingStrategy = pageFetchingStrategy }

    fun customActionReducer(customActionReducer: CustomActionReducer<Id, CK, SO, CA, CE>?) =
        apply { this.customActionReducer = customActionReducer }

    fun pagingBufferMaxSize(size: Int) = apply { this.pagingBufferMaxSize = size }

    fun build(): DefaultReducer<Id, CK, SO, CA, CE> {
        val retriesRepository: RetriesRepository<Id, CK, SO> = RetriesRepository()

        val mutablePagingBuffer: MutablePagingBuffer<Id, CK, SO> = mutablePagingBuffer(pagingBufferMaxSize)

        val queueManager: QueueManager<Id, CK> =
            DefaultQueueManager(
                pagingConfig,
                anchorPosition,
                pageFetchingStrategy,
                dispatcherInjector = dispatcherInjector,
                pagingStateManager,
                mutablePagingBuffer,
                logger,
                childScope,
            )

        return DefaultReducer(
            childScope = childScope,
            initialKey = initialKey,
            anchorPosition = anchorPosition,
            pagingConfig = pagingConfig,
            errorHandlingStrategy = errorHandlingStrategy,
            aggregatingStrategy = aggregatingStrategy,
            customActionReducer = customActionReducer,
            queueManager = queueManager,
            retriesRepository = retriesRepository,
            mutablePagingBuffer = mutablePagingBuffer,
            logger = logger,
        )
    }
}
