package org.mobilenativefoundation.paging.core

/**
 * A type alias for an [Effect] that loads the next page of data when the paging state is [PagingState.Data.Idle].
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched.
 */
typealias LoadNextEffect<Id, K, P, D, E, A> = Effect<Id, K, P, D, E, A, PagingAction.UpdateData<Id, K, P, D, E, A>, PagingState.Data.Idle<Id, K, P, D, E>>

/**
 * A type alias for an [Effect] that loads data when a [PagingAction.Load] action is dispatched and the paging state is [PagingState].
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched to modify the paging state.
 */
typealias AppLoadEffect<Id, K, P, D, E, A> = Effect<Id, K, P, D, E, A, PagingAction.Load<Id, K, P, D, E, A>, PagingState<Id, K, P, D, E>>

/**
 * A type alias for an [Effect] that loads data when a [PagingAction.User.Load] action is dispatched and the paging state is [PagingState.Loading].
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched to modify the paging state.
 */
typealias UserLoadEffect<Id, K, P, D, E, A> = Effect<Id, K, P, D, E, A, PagingAction.User.Load<Id, K, P, D, E, A>, PagingState.Loading<Id, K, P, D, E>>

/**
 * A type alias for an [Effect] that loads more data when a [PagingAction.User.Load] action is dispatched and the paging state is [PagingState.Data.LoadingMore].
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched to modify the paging state.
 */
typealias UserLoadMoreEffect<Id, K, P, D, E, A> = Effect<Id, K, P, D, E, A, PagingAction.User.Load<Id, K, P, D, E, A>, PagingState.Data.LoadingMore<Id, K, P, D, E>>