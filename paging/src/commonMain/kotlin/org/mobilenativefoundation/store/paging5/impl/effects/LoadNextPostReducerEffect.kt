package org.mobilenativefoundation.store.paging5.impl.effects

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.core5.StoreKey
import org.mobilenativefoundation.store.paging5.Logger
import org.mobilenativefoundation.store.paging5.PagingAction
import org.mobilenativefoundation.store.paging5.PagingState
import org.mobilenativefoundation.store.paging5.PostReducerEffect
import org.mobilenativefoundation.store.paging5.QueueManagerInjector

@ExperimentalStoreApi
class LoadNextPostReducerEffect<Id : Comparable<Id>, CK : StoreKey.Collection<Id>, SO : StoreData.Single<Id>, CE : Any>(
    private val logger: Logger?,
    private val queueManagerInjector: QueueManagerInjector<Id, CK>,
) : PostReducerEffect<Id, CK, SO, CE, PagingState.Data.Idle<Id, CK, SO>, PagingAction.App.UpdateData<Id, CK, SO>> {
    override fun run(
        state: PagingState.Data.Idle<Id, CK, SO>,
        action: PagingAction.App.UpdateData<Id, CK, SO>,
        dispatch: (PagingAction) -> Unit,
    ) {
        logger?.d(
            """
            Running post reducer effect:
            Effect: Load next
            State: $state
            Action: $action
            """.trimIndent(),
        )

        if (action.page.nextKey != null) {
            val queueManager =
                queueManagerInjector.queueManager ?: throw IllegalStateException("Queue manager is not available")

            queueManager.enqueue(action.page.nextKey)
        }
    }
}
