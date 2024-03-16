package org.mobilenativefoundation.paging.core.utils.timeline

import org.mobilenativefoundation.paging.core.Effect
import org.mobilenativefoundation.paging.core.PagingAction
import org.mobilenativefoundation.paging.core.PagingSource
import org.mobilenativefoundation.paging.core.PagingState

class ErrorLoggingEffect(private val log: (error: E) -> Unit) : Effect<Id, K, P, D, E, A, PagingAction.UpdateError<Id, K, P, D, E, A>, PagingState.Error.Exception<Id, K, P, D, E>> {
    override fun invoke(action: PagingAction.UpdateError<Id, K, P, D, E, A>, state: PagingState.Error.Exception<Id, K, P, D, E>, dispatch: (PagingAction<Id, K, P, D, E, A>) -> Unit) {
        when (val error = action.error) {
            is PagingSource.LoadResult.Error.Custom -> {}
            is PagingSource.LoadResult.Error.Exception -> {
                log(TimelineError.Exception(error.error))
            }
        }
    }
}