package org.mobilenativefoundation.paging.core.impl

import org.mobilenativefoundation.paging.core.AppLoadEffect
import org.mobilenativefoundation.paging.core.Logger
import org.mobilenativefoundation.paging.core.PagingAction
import org.mobilenativefoundation.paging.core.PagingSource
import org.mobilenativefoundation.paging.core.PagingSourceCollector
import org.mobilenativefoundation.paging.core.PagingState

class DefaultAppLoadEffect<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any>(
    loggerInjector: OptionalInjector<Logger>,
    dispatcherInjector: Injector<Dispatcher<Id, K, P, D, E, A>>,
    pagingSourceCollectorInjector: Injector<PagingSourceCollector<Id, K, P, D, E, A>>,
    pagingSourceInjector: Injector<PagingSource<Id, K, P, D, E>>,
    private val jobCoordinator: JobCoordinator,
    private val stateManager: StateManager<Id, K, P, D, E>,
) : AppLoadEffect<Id, K, P, D, E, A> {
    private val logger = lazy { loggerInjector.inject() }
    private val dispatcher = lazy { dispatcherInjector.inject() }
    private val pagingSourceCollector = lazy { pagingSourceCollectorInjector.inject() }
    private val pagingSource = lazy { pagingSourceInjector.inject() }

    override fun invoke(action: PagingAction.Load<Id, K, P, D, E, A>, state: PagingState<Id, K, P, D, E>, dispatch: (PagingAction<Id, K, P, D, E, A>) -> Unit) {
        logger.value?.log(
            """Running post reducer effect:
                Effect: App load
                State: $state
                Action: $action
            """.trimIndent(),
        )

        jobCoordinator.launchIfNotActive(action.key) {
            val params = PagingSource.LoadParams(action.key, true)
            pagingSourceCollector.value(
                params,
                pagingSource.value.stream(params),
                stateManager.state.value,
                dispatcher.value::dispatch
            )
        }
    }

}