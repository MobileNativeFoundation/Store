package org.mobilenativefoundation.paging.core

/**
 * The [Reducer] is responsible for taking the current [PagingState] and a dispatched [PagingAction],
 * and producing a new [PagingState] based on the action and the current state.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched to modify the paging state.
 */
interface Reducer<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any> {
    /**
     * Reduces the current [PagingState] based on the dispatched [PagingAction] and returns a new [PagingState].
     *
     * This function is called whenever a [PagingAction] is dispatched to update the paging state.
     * It should handle the action and produce a new state based on the current state and the action.
     *
     * @param action The dispatched [PagingAction] to be reduced.
     * @param state The current [PagingState] before applying the action.
     * @return The new [PagingState] after applying the action to the current state.
     */
    suspend fun reduce(
        action: PagingAction<Id, K, P, D, E, A>,
        state: PagingState<Id, K, P, D, E>
    ): PagingState<Id, K, P, D, E>
}