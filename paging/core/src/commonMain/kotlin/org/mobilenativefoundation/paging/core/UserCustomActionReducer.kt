package org.mobilenativefoundation.paging.core

/**
 * Represents a reducer for handling [PagingAction.User.Custom] actions.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched.
 */
interface UserCustomActionReducer<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any> {
    /**
     * Reduces the current [PagingState] based on the custom user action.
     *
     * @param action The custom user action to reduce.
     * @param state The current [PagingState] before applying the action.
     * @return The new [PagingState] after applying the custom user action.
     */
    fun reduce(
        action: PagingAction.User.Custom<Id, K, P, D, E, A>,
        state: PagingState<Id, K, P, D, E>
    ): PagingState<Id, K, P, D, E>
}
