package org.mobilenativefoundation.store.paging5.impl

import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.core5.StoreData
import org.mobilenativefoundation.store.paging5.ErrorHandlingStrategy
import org.mobilenativefoundation.store.paging5.Pager
import org.mobilenativefoundation.store.paging5.PagingConfig

@ExperimentalStoreApi
internal class RealPagingErrorManager<Id : Comparable<Id>, SO : StoreData.Single<Id>>(
    private val pagingConfig: PagingConfig,
    private val stateManager: PagingStateManager<Id, SO>,
    private val retryLast: () -> Unit,
) : PagingErrorManager {

    override fun handleError(error: Throwable) {
        when (val strategy = pagingConfig.errorHandlingStrategy) {
            is ErrorHandlingStrategy.Custom -> strategy.action.invoke()
            ErrorHandlingStrategy.Ignore -> {}
            ErrorHandlingStrategy.RetryLast -> retryLast
        }

        val pagingError = Pager.PagingError(error)
        stateManager.updateError(pagingError)
    }
}