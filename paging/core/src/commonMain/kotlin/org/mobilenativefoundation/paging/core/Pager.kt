package org.mobilenativefoundation.paging.core

import kotlinx.coroutines.flow.StateFlow

/**
 * [Pager] is responsible for coordinating the paging process and providing access to the paging state and data.
 * This is the main entry point for the [org.mobilenativefoundation.paging] library.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched to modify the paging state.
 */
interface Pager<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any> {
    /**
     * The current paging state exposed as a [StateFlow].
     * The paging state represents the current state of the paging data, including loaded pages, errors, and loading status.
     * Observers can collect this flow to react to changes in the paging state.
     */
    val state: StateFlow<PagingState<Id, K, P, D, E>>

    /**
     * Dispatches a user-initiated [PagingAction] to modify the paging state.
     *
     * User actions can be dispatched to trigger specific behaviors or modifications to the paging state.
     * The dispatched action will go through the configured [Middleware] chain and [Reducer] before updating the paging state.
     * After updating the state, the dispatched action will launch each configured post-reducer [Effect].
     *
     * @param action The user-initiated [PagingAction] to dispatch.
     */
    fun dispatch(action: PagingAction.User<Id, K, P, D, E, A>)
}

