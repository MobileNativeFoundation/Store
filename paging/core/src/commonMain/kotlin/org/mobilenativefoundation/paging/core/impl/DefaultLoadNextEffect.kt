package org.mobilenativefoundation.paging.core.impl

import org.mobilenativefoundation.paging.core.LoadNextEffect
import org.mobilenativefoundation.paging.core.Logger
import org.mobilenativefoundation.paging.core.PagingAction
import org.mobilenativefoundation.paging.core.PagingState

class DefaultLoadNextEffect<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any>(
    loggerInjector: OptionalInjector<Logger>,
    queueManagerInjector: Injector<QueueManager<K, P>>,
) : LoadNextEffect<Id, K, P, D, E, A> {

    private val logger = lazy { loggerInjector.inject() }
    private val queueManager = lazy { queueManagerInjector.inject() }

    override fun invoke(action: PagingAction.UpdateData<Id, K, P, D, E, A>, state: PagingState.Data.Idle<Id, K, P, D, E>, dispatch: (PagingAction<Id, K, P, D, E, A>) -> Unit) {
        logger.value?.log(
            """
            Running post reducer effect:
                Effect: Load next
                State: $state
                Action: $action
            """.trimIndent(),
        )

        action.data.collection.nextKey?.key?.let {
            queueManager.value.enqueue(action.data.collection.nextKey)
        }
    }
}