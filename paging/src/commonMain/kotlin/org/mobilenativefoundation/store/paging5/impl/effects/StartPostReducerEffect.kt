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
class StartPostReducerEffect<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CE : Any>(
    private val initialKey: CK,
    private val logger: Logger?,
    private val jobCoordinator: JobCoordinator,
    private val pagingCollector: PagingCollector<Id, CK, SO, CE>,
    private val pagingSource: PagingSource<Id, CK, SO>,
    private val pagingStateManager: PagingStateManager<Id, CK, SO, CE>,
    private val dispatcherInjector: DispatcherInjector,
) :
    PostReducerEffect<Id, CK, SO, CE, PagingState.LoadingInitial<Id, CK, SO>, PagingAction.App.Start> {
    override fun run(
        state: PagingState.LoadingInitial<Id, CK, SO>,
        action: PagingAction.App.Start,
        dispatch: (PagingAction) -> Unit,
    ) {
        logger?.d(
            """
            Running post reducer effect:
            Effect: Start
            State: $state
            Action: $action
            """.trimIndent(),
        )

        jobCoordinator.launchIfNotActive(initialKey) {
            val params = PagingSource.LoadParams(initialKey, true)
            pagingCollector(
                params,
                pagingSource.stream(params),
                pagingStateManager.state.value,
                dispatcherInjector.dispatch,
            )
        }
    }
}
