package org.mobilenativefoundation.store.paging5

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.impl.DefaultPageAggregatingStrategy
import org.mobilenativefoundation.store.paging5.impl.DefaultPageFetchingStrategy
import org.mobilenativefoundation.store.paging5.impl.RealJobCoordinator
import org.mobilenativefoundation.store.paging5.impl.RealPager
import org.mobilenativefoundation.store.paging5.impl.RealPagingErrorManager
import org.mobilenativefoundation.store.paging5.impl.RealPagingStateManager


@ExperimentalStoreApi
interface Pager<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> {
    val data: Flow<PagingData<Id, SO>>

    // Exposing error flow for UI components to observe and react to errors
    val errors: Flow<PagingError?>

    data class PagingData<Id : Any, SO : StoreData.Single<Id>>(
        val items: List<SO>
    )

    data class PagingError(val error: Throwable)

    companion object {

        fun <Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>> create(
            scope: CoroutineScope,
            initialKey: CK,
            anchorPosition: StateFlow<Id?>,
            pagingConfig: PagingConfig = PagingConfig(),
            aggregator: PageAggregatingStrategy<Id, CK, SO> = DefaultPageAggregatingStrategy(),
            pageFetchingStrategy: PageFetchingStrategy<Id, CK, SO> = DefaultPageFetchingStrategy(),
            pagingSourceFactory: () -> PagingSource<Id, CK, SO>
        ): Pager<Id, CK, SO> {

            val stateManager = RealPagingStateManager<Id, SO>()

            return RealPager(
                scope = scope,
                initialKey = initialKey,
                anchorPosition = anchorPosition,
                pagingConfig = pagingConfig,
                aggregator = aggregator,
                pageFetchingStrategy = pageFetchingStrategy,
                stateManager = stateManager,
                errorManagerFactory = { retry ->
                    RealPagingErrorManager(pagingConfig, stateManager, retry)
                },
                jobCoordinatorFactory = { childScope ->
                    RealJobCoordinator(childScope)
                },
                pagingSourceFactory
            )
        }
    }
}

