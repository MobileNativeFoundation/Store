package org.mobilenativefoundation.store.paging5.impl.effects

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.DispatcherInjector
import org.mobilenativefoundation.store.paging5.JobCoordinator
import org.mobilenativefoundation.store.paging5.Logger
import org.mobilenativefoundation.store.paging5.PagingAction
import org.mobilenativefoundation.store.paging5.PagingCollector
import org.mobilenativefoundation.store.paging5.PagingSource
import org.mobilenativefoundation.store.paging5.PagingState
import org.mobilenativefoundation.store.paging5.PagingStateManager
import org.mobilenativefoundation.store.paging5.PostReducerEffect

@ExperimentalStoreApi
class AppLoadPostReducerEffect<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CE : Any>(
    private val initialKey: CK,
    private val logger: Logger?,
    private val jobCoordinator: JobCoordinator,
    private val pagingCollector: PagingCollector<Id, CK, SO, CE>,
    private val pagingSource: PagingSource<Id, CK, SO>,
    private val pagingStateManager: PagingStateManager<Id, CK, SO, CE>,
    private val dispatcherInjector: DispatcherInjector,
) : PostReducerEffect<Id, CK, SO, CE, PagingState.Data.LoadingMore<Id, CK, SO>, PagingAction.App.Load<Id, CK>> {
    override fun run(
        state: PagingState.Data.LoadingMore<Id, CK, SO>,
        action: PagingAction.App.Load<Id, CK>,
        dispatch: (PagingAction) -> Unit,
    ) {
        logger?.d(
            """
            Running post reducer effect:
            Effect: App load
            State: $state
            Action: $action
            """.trimIndent(),
        )

        jobCoordinator.launchIfNotActive(action.key) {
            val params = PagingSource.LoadParams(action.key, true)
            pagingCollector(
                params,
                pagingSource.stream(params),
                pagingStateManager.state.value,
                dispatcherInjector.dispatch,
            )
        }
    }
}
