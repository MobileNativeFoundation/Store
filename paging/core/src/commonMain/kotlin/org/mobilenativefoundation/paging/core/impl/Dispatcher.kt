package org.mobilenativefoundation.paging.core.impl

import org.mobilenativefoundation.paging.core.PagingAction

/**
 * A dispatcher for handling paging actions and dispatching them to the appropriate middleware and reducer.
 *
 * @param Id The type of the unique identifier for each item in the paged data.
 * @param K The type of the key used for paging.
 * @param P The type of the parameters associated with each page of data.
 * @param D The type of the data items.
 * @param E The type of errors that can occur during the paging process.
 * @param A The type of custom actions that can be dispatched to modify the paging state.
 */
interface Dispatcher<Id : Comparable<Id>, K : Any, P : Any, D : Any, E : Any, A : Any> {

    /**
     * Dispatches a paging action to the middleware and reducer chain.
     *
     * @param PA The type of the paging action being dispatched.
     * @param action The paging action to dispatch.
     * @param index The index of the middleware to start dispatching from. Default is 0.
     */
    fun <PA : PagingAction<Id, K, P, D, E, A>> dispatch(action: PA, index: Int = 0)
}
