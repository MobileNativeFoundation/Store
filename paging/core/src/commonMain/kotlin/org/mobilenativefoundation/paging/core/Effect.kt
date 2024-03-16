package org.mobilenativefoundation.paging.core

/**
 * Represents an effect that can be triggered after reducing a specific [PagingAction] and [PagingState] combination.
 *
 * Effects are side effects or additional actions that need to be performed after the state has been reduced based on a dispatched action.
 * They can be used for tasks such as loading more data, updating the UI, triggering network requests, or any other side effects that depend on the current state and action.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched to modify the paging state.
 * @param PA The specific type of [PagingAction] that triggers this effect.
 * @param S The specific type of [PagingState] that triggers this effect.
 */
interface Effect<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any, PA : PagingAction<Id, K, P, D, E, A>, S : PagingState<Id, K, P, D, E>> {
    operator fun invoke(action: PA, state: S, dispatch: (PagingAction<Id, K, P, D, E, A>) -> Unit)
}