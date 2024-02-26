package org.mobilenativefoundation.store.paging5.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.plus
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.PageAggregatingStrategy
import org.mobilenativefoundation.store.paging5.PageFetchingStrategy
import org.mobilenativefoundation.store.paging5.Pager
import org.mobilenativefoundation.store.paging5.PagingConfig
import org.mobilenativefoundation.store.paging5.PagingSource
import org.mobilenativefoundation.store.paging5.PagingState


@ExperimentalStoreApi
internal class RealPager<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>>(
    scope: CoroutineScope,
    initialKey: CK,
    private val anchorPosition: StateFlow<Id?>,
    private val pagingConfig: PagingConfig,
    private val aggregator: PageAggregatingStrategy<Id, CK, SO>,
    pageFetchingStrategy: PageFetchingStrategy<Id, CK, SO>,
    private val stateManager: PagingStateManager<Id, SO>,
    errorManagerFactory: (retry: () -> Unit) -> PagingErrorManager,
    jobCoordinatorFactory: (childScope: CoroutineScope) -> RealJobCoordinator,
    pagingSourceFactory: () -> PagingSource<Id, CK, SO>,
) : Pager<Id, CK, SO> {

    private val childScope = scope + Job()

    private val pages: LinkedHashMap<PagingSource.LoadParams<Id, CK>, PagingSource.LoadResult.Page<Id, CK, SO>> =
        linkedMapOf()

    private val currentKey = MutableStateFlow(initialKey)
    private val prefetchPosition: MutableStateFlow<Id?> = MutableStateFlow(null)

    private val pagingState: StateFlow<PagingState<Id, CK, SO>>
        get() = MutableStateFlow(
            PagingState(
                anchorPosition = anchorPosition.value,
                prefetchPosition = prefetchPosition.value,
                config = pagingConfig,
                pages = pages
            )
        )

    private val pagingSource = pagingSourceFactory()
    private val errorManager = errorManagerFactory { retryLast() }
    private val jobCoordinator = jobCoordinatorFactory(childScope)
    private val pageLoader: PageLoader<Id, CK, SO> = RealPageLoader(pagingSource, ::handlePageLoaded, ::handleLoadError)
    private val queueManager: QueueManager<Id, CK> = RealQueueManager(pagingState, pageFetchingStrategy, ::load)

    override val errors: Flow<Pager.PagingError?> = stateManager.errors
    override val data: Flow<Pager.PagingData<Id, SO>> = stateManager.data


    init {
        load(initialKey)
    }

    private fun load(key: CK) {
        currentKey.value = key

        val params = PagingSource.LoadParams(key, refresh = true)

        jobCoordinator.launchIfNotActive(params) {
            pageLoader.loadPage(params)
        }
    }

    private fun handleLoadError(error: Throwable) {
        errorManager.handleError(error)
    }

    private fun handlePageLoaded(
        params: PagingSource.LoadParams<Id, CK>,
        page: PagingSource.LoadResult.Page<Id, CK, SO>
    ) {

        pages[params] = page
        stateManager.updateData(aggregator.aggregate(pagingState.value))
        stateManager.clearError()

        page.data.last().id.let {
            prefetchPosition.value = it
        }

        if (page.nextKey != null) {
            queueManager.enqueue(page.nextKey)
        }
    }


    // Retry mechanism that can be triggered by the user action suggested in the error state
    private fun retryLast() {
        load(currentKey.value)
    }
}

