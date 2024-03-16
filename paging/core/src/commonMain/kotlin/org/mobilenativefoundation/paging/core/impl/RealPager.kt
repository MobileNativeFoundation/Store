package org.mobilenativefoundation.paging.core.impl

import kotlinx.coroutines.flow.StateFlow
import org.mobilenativefoundation.paging.core.Pager
import org.mobilenativefoundation.paging.core.PagingAction
import org.mobilenativefoundation.paging.core.PagingConfig
import org.mobilenativefoundation.paging.core.PagingKey
import org.mobilenativefoundation.paging.core.PagingState

class RealPager<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any>(
    initialKey: PagingKey<K, P>,
    stateManager: StateManager<Id, K, P, D, E>,
    pagingConfigInjector: Injector<PagingConfig>,
    private val dispatcher: Dispatcher<Id, K, P, D, E, A>,
) : Pager<Id, K, P, D, E, A> {

    private val pagingConfig = lazy { pagingConfigInjector.inject() }

    init {
        if (pagingConfig.value.prefetchDistance > 0) {
            dispatcher.dispatch(PagingAction.Load(initialKey))
        }
    }

    override val state: StateFlow<PagingState<Id, K, P, D, E>> = stateManager.state

    override fun dispatch(action: PagingAction.User<Id, K, P, D, E, A>) {
        dispatcher.dispatch(action)
    }
}
