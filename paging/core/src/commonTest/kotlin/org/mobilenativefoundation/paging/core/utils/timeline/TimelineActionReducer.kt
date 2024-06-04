package org.mobilenativefoundation.paging.core.utils.timeline

import org.mobilenativefoundation.paging.core.PagingAction
import org.mobilenativefoundation.paging.core.PagingState
import org.mobilenativefoundation.paging.core.UserCustomActionReducer

class TimelineActionReducer : UserCustomActionReducer<Id, K, P, D, E, A> {
    override fun reduce(action: PagingAction.User.Custom<Id, K, P, D, E, A>, state: PagingState<Id, K, P, D, E>): PagingState<Id, K, P, D, E> {
        return when (action.action) {
            TimelineAction.ClearData -> {
                val nextState = when (state) {
                    is PagingState.Data.ErrorLoadingMore<Id, K, P, D, E, *> -> state.copy(data = emptyList())
                    is PagingState.Data.Idle -> state.copy(data = emptyList())
                    is PagingState.Data.LoadingMore -> state.copy(data = emptyList())
                    is PagingState.Error.Custom,
                    is PagingState.Error.Exception,
                    is PagingState.Initial,
                    is PagingState.Loading -> state
                }

                nextState
            }
        }
    }

}